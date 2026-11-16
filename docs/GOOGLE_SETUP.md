# One-time Google Cloud setup

Google only lets an app touch your Drive if the app is registered in a Google Cloud project.
You create that project yourself, under your own account, so nobody else is involved and there
is nothing to pay. No key or secret is stored in this repository or in the app: Google recognises
the app by its **package name** and the **SHA-1 fingerprint** of the key that signed the APK.

| Value | Use exactly this |
|---|---|
| Package name | `com.drivelift.app` |
| SHA-1 fingerprint | `48:CF:B2:CD:AC:DE:E3:1E:11:22:CE:43:19:CC:4D:35:4B:B6:58:6C` |

## Steps

1. Open <https://console.cloud.google.com/> and sign in with the Google account whose Drive you want to use.
2. Create a project (top bar, project picker, **New project**). Any name, for example `DriveLift`.
3. **Enable the Drive API**: menu, *APIs & Services*, *Library*, search for **Google Drive API**, **Enable**.
4. **Configure the consent screen**: menu, *APIs & Services*, *OAuth consent screen* (also shown as
   *Google Auth Platform*).
   - App name: `DriveLift`. Support email and developer email: your own address.
   - Audience / user type: **External**.
   - Under **Audience**, **Test users**: add your own Gmail address. While the app is in "Testing",
     only listed test users can sign in. That is what you want for a personal app.
   - Under **Data access**, **Add or remove scopes**: add `https://www.googleapis.com/auth/drive`.
5. **Create the Android client**: *Clients* (or *Credentials*, *Create credentials*, *OAuth client ID*).
   - Application type: **Android**
   - Package name and SHA-1: the two values in the table above.
6. Install the app, tap **Connect**, pick your account. Google shows a "Google hasn't verified this
   app" notice because the project is in testing; choose **Continue**, then allow Drive access.

## Why the debug keystore is in the repository

The SHA-1 above belongs to `keystore/debug.keystore`. Committing it means the APK built on your PC
and the APK built by GitHub Actions are signed with the same key, so the one OAuth client works for
both and updates install over each other. A debug keystore is not a secret (its password is the
standard `android`), but it also gives no protection: keep this repository private, and if you ever
publish the app, create a separate release key that is never committed and register its SHA-1 as a
second Android client.

To print the fingerprint yourself:

```bash
keytool -list -v -keystore keystore/debug.keystore -storepass android
```

## Why the full Drive scope

The app lists your existing Drive folders and uploads into folders it did not create. The narrower
`drive.file` scope only covers files the app itself created, so the folder picker would be empty.

## Troubleshooting

| Symptom | Cause |
|---|---|
| "Could not reach Google sign-in (10: ...)" or `DEVELOPER_ERROR` | Package name or SHA-1 in the Android client does not match the installed APK |
| "Access blocked" or error 403 `access_denied` on the consent page | Your account is not in the Test users list |
| Folder list fails with "Drive error 403: ... API has not been used" | The Google Drive API is not enabled in the project |
| Transfers show "Sign in to Google Drive again" | Access was revoked or expired; open the app and tap Connect |
