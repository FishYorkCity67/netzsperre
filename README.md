# Netzsperre

Android-App, die einzelnen Apps das Internet sperrt – ohne Root.

## So funktioniert es

Netzsperre startet ein lokales VPN, das nur für die gesperrten Apps gilt. Deren Datenverkehr wird verworfen, alle anderen Apps sind nicht betroffen. Es gibt keinen Server: Nichts verlässt das Gerät.

- Apps auswählen und mit einem Schalter sperren
- Kachel in den Schnelleinstellungen zum schnellen An- und Ausschalten
- Startet nach einem Neustart oder Update automatisch wieder
- Systemdienste lassen sich im Menü einblenden

Android erlaubt immer nur ein aktives VPN. Solange Netzsperre läuft, kann also kein anderes VPN laufen.

Reines Java ohne AndroidX, die APK ist unter 50 KB groß.

## Bauen

Voraussetzungen: JDK 21 und das Android SDK.

Der Signierschlüssel gehört nicht ins Repository. Leg dir einen eigenen an:

```powershell
mkdir keystore
keytool -genkeypair -v -keystore keystore\netzsperre.keystore -alias netzsperre -keyalg RSA -keysize 2048 -validity 10000
```

Daneben kommt `keystore/key.properties`:

```properties
storePassword=dein-passwort
keyAlias=netzsperre
```

Dann:

```powershell
.\gradlew.bat ':app:assembleRelease'
```

Unter Windows landet die APK in `%LOCALAPPDATA%\NetzSperreBuild\app\outputs\apk\release\`.

## Lizenz

MIT – siehe [LICENSE](LICENSE).
