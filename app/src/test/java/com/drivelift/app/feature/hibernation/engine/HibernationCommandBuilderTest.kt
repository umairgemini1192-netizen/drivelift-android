package com.drivelift.app.feature.hibernation.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HibernationCommandBuilderTest {

    @Test
    fun validatesPackageNamesStrictly() {
        assertTrue(HibernationCommandBuilder.isValidPackageName("com.example.social_app"))
        assertTrue(HibernationCommandBuilder.isValidPackageName("org.mozilla.firefox"))
        assertEquals(
            "com.example.trimmed",
            HibernationCommandBuilder.validatePackageName("  com.example.trimmed  "),
        )

        val invalidInputs = listOf(
            "",
            "   ",
            "singleword",
            "com.example.app; rm -rf /",
            "com.example.app && id",
            "com.example.app$(whoami)",
            "com.example.app\nid",
            "com.example..double",
            "com.123bad.app",
            "com.example.with-dash",
            "a." + "b".repeat(255),
        )
        for (invalid in invalidInputs) {
            assertFalse("Expected invalid package name: $invalid", HibernationCommandBuilder.isValidPackageName(invalid))
        }
    }

    @Test
    fun buildsDefaultAndSuspendSleepSequences() {
        val defaultSeq = HibernationCommandBuilder.buildSleepSequence("com.example.social")
        assertEquals(
            listOf(
                "appops set com.example.social RUN_ANY_IN_BACKGROUND ignore",
                "am set-inactive com.example.social true",
                "am force-stop com.example.social",
            ),
            defaultSeq.map { it.commandLine },
        )

        val withSuspend = HibernationCommandBuilder.buildSleepSequence(
            packageName = "com.example.social",
            options = HibernationCommandOptions(suspendPackage = true),
        )
        assertEquals(
            listOf(
                "appops set com.example.social RUN_ANY_IN_BACKGROUND ignore",
                "am set-inactive com.example.social true",
                "am force-stop com.example.social",
                "pm suspend com.example.social",
            ),
            withSuspend.map { it.commandLine },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAlwaysProtectedSelfPackage() {
        HibernationCommandBuilder.buildSleepSequence("com.drivelift.app")
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUserWhitelistedPackage() {
        HibernationCommandBuilder.buildSleepSequence(
            packageName = "com.example.keepalive",
            protectedPackages = setOf("com.example.keepalive"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesInjectedShellMetacharacters() {
        HibernationCommandBuilder.buildSleepSequence("com.example.app;reboot")
    }

    @Test
    fun buildsWakeSequence() {
        val wakeSeq = HibernationCommandBuilder.buildWakeSequence("com.example.social")
        assertEquals(
            listOf(
                "pm unsuspend com.example.social",
                "appops set com.example.social RUN_ANY_IN_BACKGROUND allow",
                "am set-inactive com.example.social false",
            ),
            wakeSeq.map { it.commandLine },
        )
    }
}
