# Auto DJ (rebuilt from the storyboard)
Open this folder in Android Studio (Koala or newer, JDK 17). Let it sync (it creates the Gradle wrapper), then Build > Build APK, or run
`./gradlew testDebugUnitTest lintDebug assembleDebug`.

## Status: NOT COMPILED. Nothing here has been built, linted or run on a device.
Written without an Android SDK. Expect a few compile fixes on first sync.

| Area | State |
|---|---|
| Tabs Home/Routine/Music/Settings, Stop all, remaining time | Written, untested |
| Document picker + persisted URI, duration, readable status, Preview/Replace/Remove | Written, untested |
| Media3 service, media notification, audio focus, "Playing" only after real playback | Written, untested |
| 6:00 AM daily alarm (setAlarmClock), 60 min, weekday playlist, fade, greeting, shuffle, repeat-to-fill, snooze, fallback sound | Written, untested |
| Reboot / time-zone reschedule, duplicate-trigger guard, overlap restart | Written, untested |
| YouTube link validation + Open in YouTube (unit-tested logic only) | Written, untested |
| Voice tap-to-talk with error recovery, spoken replies | Written, untested |
| Setup checks: pass only when verified (preview started, TTS started, alarm fired with screen off) | Written, untested |
| Unit tests (link validation, next trigger, weekday index) | Written, not run |

## Not implemented
Voice changes to schedules with Undo; Hey Google / App Actions; schema migration from your OLD app's data (needs the old source); Room database (uses SharedPreferences JSON); custom app icon; dark theme uses stock Material colours.

## Real-device checklist
1. Install; allow notifications. 2. Music tab: add a song for today, tap Preview. 3. Home > Test now: verify audio, notification Stop, lock-screen controls. 4. Settings: run Voice greeting test. 5. Settings: Locked-screen test, lock phone, confirm PASSED. 6. Set 6:00 AM routine, leave overnight, confirm it plays and stops after 60 min. 7. Reboot, confirm Next alarm still shown. 8. Remove a song's file, confirm it is skipped and logged. 9. Snooze, confirm stop time unchanged. 10. Start a Google Meet recording, tap Speak, confirm busy message.
