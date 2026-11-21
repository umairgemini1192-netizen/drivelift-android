package com.drivelift.app.feature.storageintelligence.scanner

import com.drivelift.app.feature.storageintelligence.model.DuplicateGroupModel
import com.drivelift.app.feature.storageintelligence.model.DuplicateMatchKind
import com.drivelift.app.feature.storageintelligence.model.HashAlgorithm
import com.drivelift.app.feature.storageintelligence.model.ScanScope
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.text.Normalizer

data class DuplicateDetectionOutput(
    val groups: List<DuplicateGroupModel>,
    val filesPartialHashed: Int,
    val filesFullHashed: Int,
    val unreadableCount: Int,
)

/**
 * Unified local + multi-cloud duplicate detector.
 *
 * Pipeline:
 * 1. Filters to non-trashed, non-folder files with `sizeBytes > 0` inside [scope].
 * 2. Buckets by exact `sizeBytes` (discarding unique sizes).
 * 3. For local-only collisions without cloud checksums, runs fast partial hashing
 *    (first 64 KB + last 64 KB + size) and computes full dual SHA-256/MD5 only on
 *    partial-hash collisions. When cloud nodes in the same size bucket provide MD5/SHA-256,
 *    local candidates in that size bucket compute full dual digests so exact cross-source
 *    matches are found without downloading cloud file bodies.
 * 4. Emits `EXACT_HASH` groups for matching strong checksums and `PROBABLE_MATCH` groups
 *    for remaining same-size + normalized-filename matches whose checksums do not conflict.
 */
class CrossSourceDuplicateEngine(
    private val localByteSource: LocalByteSource,
    private val partialWindowBytes: Int = LocalByteSource.DEFAULT_PARTIAL_WINDOW_BYTES,
) {

    suspend fun detect(
        allNodes: List<UnifiedFileNode>,
        scope: ScanScope,
        singleCloudSourceId: String? = null,
        onHashedFile: suspend (node: UnifiedFileNode, deltaBytes: Long) -> Unit = { _, _ -> },
    ): DuplicateDetectionOutput {
        val scoped = filterByScope(allNodes, scope, singleCloudSourceId)
            .filter { !it.isFolder && !it.isTrashed && it.sizeBytes > 0L }

        val sizeBuckets = scoped
            .groupBy { it.sizeBytes }
            .values
            .filter { it.size >= 2 }
            .sortedByDescending { it.first().sizeBytes }

        val groups = ArrayList<DuplicateGroupModel>()
        var partialCount = 0
        var fullCount = 0
        var unreadableCount = 0

        for (bucket in sizeBuckets) {
            currentCoroutineContext().ensureActive()
            val unreadableIds = HashSet<String>()
            val resolvedNodes = ArrayList<UnifiedFileNode>(bucket.size)
            val localNodes = bucket.filter { it.isLocal }
            val cloudNodes = bucket.filter { it.isCloud }
            val cloudHasShaOrMd5 = cloudNodes.any { node ->
                node.strongDigests().any { (alg, _) -> alg == HashAlgorithm.SHA256 || alg == HashAlgorithm.MD5 }
            }

            resolvedNodes += cloudNodes

            if (localNodes.isNotEmpty()) {
                if (localNodes.size >= 2 && !cloudHasShaOrMd5) {
                    val byPartial = LinkedHashMap<String, MutableList<UnifiedFileNode>>()
                    for (local in localNodes) {
                        currentCoroutineContext().ensureActive()
                        val partial = localByteSource.readPartialHash(local, partialWindowBytes)
                        if (partial == null) {
                            unreadableCount++
                            unreadableIds += local.id
                            continue
                        }
                        partialCount++
                        byPartial.getOrPut(partial) { ArrayList() } += local
                    }
                    for ((partial, partialBucket) in byPartial) {
                        if (partialBucket.size < 2) {
                            resolvedNodes += partialBucket.first().copy(
                                hashAlgorithm = HashAlgorithm.PARTIAL_64K_SHA256,
                                hashValue = partial,
                            )
                            continue
                        }
                        for (candidate in partialBucket) {
                            currentCoroutineContext().ensureActive()
                            var trackedDelta = 0L
                            val dual = localByteSource.computeFullDualDigest(candidate) { delta ->
                                trackedDelta += delta
                            }
                            if (dual == null) {
                                unreadableCount++
                                unreadableIds += candidate.id
                                continue
                            }
                            fullCount++
                            onHashedFile(candidate, trackedDelta)
                            resolvedNodes += candidate.copy(
                                hashAlgorithm = HashAlgorithm.SHA256,
                                hashValue = dual.sha256,
                                secondaryHashAlgorithm = HashAlgorithm.MD5,
                                secondaryHashValue = dual.md5,
                            )
                        }
                    }
                } else if (cloudHasShaOrMd5) {
                    for (local in localNodes) {
                        currentCoroutineContext().ensureActive()
                        var trackedDelta = 0L
                        val dual = localByteSource.computeFullDualDigest(local) { delta ->
                            trackedDelta += delta
                        }
                        if (dual == null) {
                            unreadableCount++
                            unreadableIds += local.id
                            continue
                        }
                        fullCount++
                        onHashedFile(local, trackedDelta)
                        resolvedNodes += local.copy(
                            hashAlgorithm = HashAlgorithm.SHA256,
                            hashValue = dual.sha256,
                            secondaryHashAlgorithm = HashAlgorithm.MD5,
                            secondaryHashValue = dual.md5,
                        )
                    }
                } else {
                    // Single local file + cloud file without MD5/SHA256: eligible for probable match.
                    resolvedNodes += localNodes
                }
            }

            val exactMatchedIds = HashSet<String>()
            val exactGroups = buildExactHashGroups(resolvedNodes)
            for (group in exactGroups) {
                groups += group
                group.items.forEach { exactMatchedIds += it.id }
            }

            val remaining = resolvedNodes.filter { it.id !in exactMatchedIds && it.id !in unreadableIds }
            if (remaining.size >= 2) {
                groups += buildProbableGroups(remaining)
            }
        }

        val sortedGroups = groups.sortedWith(
            compareByDescending<DuplicateGroupModel> { it.reclaimableBytes }.thenBy { it.groupKey },
        )
        return DuplicateDetectionOutput(
            groups = sortedGroups,
            filesPartialHashed = partialCount,
            filesFullHashed = fullCount,
            unreadableCount = unreadableCount,
        )
    }

    private fun filterByScope(
        nodes: List<UnifiedFileNode>,
        scope: ScanScope,
        singleCloudSourceId: String?,
    ): List<UnifiedFileNode> = when (scope) {
        ScanScope.LOCAL_ONLY -> nodes.filter { it.isLocal }
        ScanScope.SINGLE_CLOUD -> nodes.filter { node ->
            node.isCloud && (singleCloudSourceId == null || node.sourceId == singleCloudSourceId)
        }
        ScanScope.ALL_CLOUDS -> nodes.filter { it.isCloud }
        ScanScope.CROSS_SOURCE -> nodes
    }

    private fun buildExactHashGroups(nodes: List<UnifiedFileNode>): List<DuplicateGroupModel> {
        if (nodes.size < 2) return emptyList()
        val parent = IntArray(nodes.size) { it }
        fun find(i: Int): Int {
            var cur = i
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]
                cur = parent[cur]
            }
            return cur
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        val firstOwner = HashMap<Pair<HashAlgorithm, String>, Int>()
        nodes.forEachIndexed { idx, node ->
            for (digest in node.strongDigests()) {
                val existing = firstOwner.putIfAbsent(digest, idx)
                if (existing != null) union(existing, idx)
            }
        }

        val components = LinkedHashMap<Int, MutableList<UnifiedFileNode>>()
        nodes.forEachIndexed { idx, node ->
            if (node.strongDigests().isNotEmpty()) {
                components.getOrPut(find(idx)) { ArrayList() } += node
            }
        }

        val out = ArrayList<DuplicateGroupModel>()
        for (members in components.values) {
            if (members.size < 2) continue
            val sortedMembers = members.sortedWith(
                compareBy<UnifiedFileNode> { it.modifiedAt }.thenBy { it.sourceId }.thenBy { it.id },
            )
            val (repAlg, repHash) = chooseRepresentativeDigest(sortedMembers)
            val size = sortedMembers.first().sizeBytes
            val isCrossSource = sortedMembers.mapTo(HashSet()) { it.sourceId }.size > 1
            val key = "exact:$size:${repAlg.name}:$repHash"
            out += DuplicateGroupModel(
                groupKey = key,
                matchKind = DuplicateMatchKind.EXACT_HASH,
                hashAlgorithm = repAlg,
                hashValue = repHash,
                normalizedName = null,
                sizeBytes = size,
                reclaimableBytes = size * (sortedMembers.size - 1),
                isCrossSource = isCrossSource,
                items = sortedMembers,
            )
        }
        return out
    }

    private fun buildProbableGroups(nodes: List<UnifiedFileNode>): List<DuplicateGroupModel> {
        val byName = nodes.groupBy { normalizeFileName(it.name) }
        val out = ArrayList<DuplicateGroupModel>()
        for ((normName, nameMembers) in byName) {
            if (normName.isBlank() || nameMembers.size < 2) continue
            val sorted = nameMembers.sortedWith(
                compareBy<UnifiedFileNode> { it.modifiedAt }.thenBy { it.sourceId }.thenBy { it.id },
            )
            // Partition into conflict-free clusters so two files with known different hashes
            // under the same strong algorithm are never grouped as probable duplicates.
            val clusters = ArrayList<MutableList<UnifiedFileNode>>()
            for (candidate in sorted) {
                val targetCluster = clusters.firstOrNull { cluster ->
                    cluster.none { existing -> hasStrongDigestConflict(existing, candidate) }
                }
                if (targetCluster != null) {
                    targetCluster += candidate
                } else {
                    clusters += mutableListOf(candidate)
                }
            }
            clusters.forEachIndexed { clusterIdx, cluster ->
                if (cluster.size < 2) return@forEachIndexed
                val size = cluster.first().sizeBytes
                val isCrossSource = cluster.mapTo(HashSet()) { it.sourceId }.size > 1
                val nameDigest = shortHexSha256("$size:$normName:$clusterIdx")
                val firstHashed = cluster.firstOrNull { it.hashValue != null }
                out += DuplicateGroupModel(
                    groupKey = "probable:$size:$nameDigest",
                    matchKind = DuplicateMatchKind.PROBABLE_MATCH,
                    hashAlgorithm = firstHashed?.hashAlgorithm ?: HashAlgorithm.NONE,
                    hashValue = firstHashed?.hashValue,
                    normalizedName = normName,
                    sizeBytes = size,
                    reclaimableBytes = size * (cluster.size - 1),
                    isCrossSource = isCrossSource,
                    items = cluster,
                )
            }
        }
        return out
    }

    private fun hasStrongDigestConflict(a: UnifiedFileNode, b: UnifiedFileNode): Boolean {
        val mapA = a.strongDigests().toMap()
        for ((alg, hashB) in b.strongDigests()) {
            val hashA = mapA[alg] ?: continue
            if (!hashA.equals(hashB, ignoreCase = true)) return true
        }
        return false
    }

    private fun chooseRepresentativeDigest(nodes: List<UnifiedFileNode>): Pair<HashAlgorithm, String> {
        val priority = listOf(
            HashAlgorithm.SHA256,
            HashAlgorithm.MD5,
            HashAlgorithm.QUICK_XOR,
            HashAlgorithm.CONTENT_HASH,
        )
        val counts = HashMap<Pair<HashAlgorithm, String>, Int>()
        for (node in nodes) {
            for (digest in node.strongDigests()) {
                counts[digest] = (counts[digest] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<Pair<HashAlgorithm, String>, Int>> { it.value }
                    .thenBy { priority.indexOf(it.key.first).let { idx -> if (idx < 0) 99 else idx } }
                    .thenBy { it.key.second },
            )
            .first()
            .key
    }

    private fun UnifiedFileNode.strongDigests(): List<Pair<HashAlgorithm, String>> = buildList {
        normalizeHash(hashAlgorithm, hashValue)?.let { if (hashAlgorithm.isStrongFullFile) add(hashAlgorithm to it) }
        normalizeHash(secondaryHashAlgorithm, secondaryHashValue)?.let {
            if (secondaryHashAlgorithm.isStrongFullFile) add(secondaryHashAlgorithm to it)
        }
    }

    private fun normalizeHash(alg: HashAlgorithm, raw: String?): String? {
        if (alg == HashAlgorithm.NONE || raw.isNullOrBlank()) return null
        val cleaned = raw.trim().trim('"').lowercase()
        return cleaned.takeIf { it.isNotEmpty() }
    }

    companion object {
        fun normalizeFileName(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFC)
                .trim()
                .replace(Regex("\\s+"), " ")
                .lowercase()

        private fun shortHexSha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
    }
}
