# Changelog

Piume shows the section for its own version in **Piume settings → Updates** after an update.
The build stops if `versionName` has no section here.

Write every entry in ASD-STE100 Simplified Technical English:

- Write one idea in each sentence. Use 20 words or fewer in each sentence.
- Use the active voice and the present tense.
- Write instructions as commands: "Select **Updates**", not "**Updates** can be selected".
- Use one word for one thing. Use the names that Piume shows on the screen.
- Start each entry with "- ". Piume shows each entry as one item. It removes `**` marks.

Put changes that are not released under `## Unreleased`. Rename that heading to the version number when you release.

## Unreleased

- The top bar has a voice search button. It opens the voice search of the TV.
- You can show the clock in 24-hour or 12-hour format. Select **Piume settings → Appearance & wallpapers → Clock**.
- **Night mode** makes the home screen darker and stops Spotlight between the hours you choose. Turn it on in **Piume settings → Appearance & wallpapers → Night mode**.
- You can save a backup of your Piume settings on your phone and restore it later. Select **Piume settings → System → Backup and restore**. The backup has your TMDB key, but not your photos or the lock PIN.
## 1.5

- Spotlight keeps its images on the TV. It downloads each image one time, not each time it shows the title.
- Spotlight gets the next image before it shows it. The next title appears without a wait.
- Spotlight shows the trending titles in a random order.
- Spotlight stops after 15 minutes without a key press. Press a key to start it again.
- Security: other apps cannot make Piume start their apps through the update screen.
- Security: a card in Continue Watching can only open the app that added it.
- Security: Android no longer copies Piume settings and your TMDB key to backups.
- You can enter your TMDB API key from your phone. Select **Piume settings → TMDB Spotlight → TMDB API Key → Enter from your phone**, then scan the code. Piume checks the key with TMDB.
- Security: the phone code for wallpapers and the TMDB key is now long and random. Other people on your Wi-Fi cannot guess it.
- The phone pages are in the language of the TV.
- Spotlight shows a streaming service only if the title is on it in your country. Nuvio, Stremio and YouTube trailer always show. To show all the services, turn off **Piume settings → TMDB Spotlight → Only services that have the title**.
- **Piume settings → System → Recent problems** shows the last things that did not work, for example a failed download. You can read them on your phone too.
- The automatic theme waits until you stop using the TV before it changes.
- Updates and weather downloads no longer slow down the rows.

## 1.4

- Plume Launcher is now Piume, a fork by mccoy88f. Piume installs next to Plume and updates from its own releases.
- You can set the weather location by hand. Select **Piume settings → Appearance & wallpapers → Weather location**.
- **Piume settings → System → Home button** is now a switch. Use it to turn the Home button on or off.
- All the text in Piume is now in one file. This makes translation easier.
- Piume is now in Italian. It uses the language of the TV.
- A guided setup makes the Home button open Piume. To do it again, select **Piume settings → System → Set up Home button**.
- A red dot on the Piume settings icon tells you about a new update or new update notes.
- **Piume settings → System → Updates** shows what is new in this version.
- You can put a short app row on the left, in the centre or on the right. Select **Piume settings → Apps row → App row position**.
- Piume settings are now in groups. Select a group to see its settings.
- The top bar has a TV inputs button. Use it to change to an HDMI input.
- **TMDB Spotlight** shows trending films and series above the apps row. Add your TMDB API key in **Piume settings → TMDB Spotlight**.
- Spotlight goes to the next title every 5 seconds. Select **Piume settings → TMDB Spotlight → Next title every** to change the time.
- Use ◀ and ▶ on Spotlight to go to the previous or next title.
- Select an app under Spotlight to open the title in that app. Nuvio and Stremio open the title page.
- **YouTube trailer** under Spotlight plays the trailer from TMDB in your TMDB language. If TMDB has no trailer, it searches YouTube.
- Focused items are easier to see in the light theme and in the dark theme.
- An app with no recommendations shows nothing under the apps row.
- Recommended TV channels are in channel number order.
- Channel cards from a TV app show all of the card. The name on the card does not touch the border.
- When the focused item is below the bottom of the screen, the home screen moves up to show it.
- The TMDB Spotlight image is easier to see. It has less shade at the top and at the bottom.
- Fix: on Android 8.0 and 8.1, the home screen closed when it started.
# Plume Launcher releases

These are the releases of Plume Launcher by aradhyas8, before the fork.

## 1.3

- You can use a folder of photos as your wallpaper. The folder can be on the TV or on a USB drive. Select **Plume settings → Folder photo changes** to set how often the photo changes.
- **Photo fade** removes the dark area at the bottom of your photos.
- You can use your photos as the screensaver. Select **Plume settings → Screensaver → Your photos**.
- **Lock layout** keeps the home screen safe from changes. Set a 4-digit PIN in **Plume settings → Lock layout**. Apps still open without the PIN.
- Hold OK on an app to show **Move**, **Remove from row** and **App info**.
- Hold OK on a Continue Watching card to show **Remove** and **Open app**.
- Continue Watching shows a maximum of three shows from each app.
- **Launcher settings** is now **Plume settings**. Back goes up one page.
- **Remove a photo…** deletes wallpapers that you uploaded.
- The Home button switch opens the correct settings screen on more TVs.

## 1.2.1

- Fix: with **Icon style → Colour**, some app icons stayed grey until Plume started again.

## 1.2

- Plume updates itself. It looks for a new version one time each day. Select **Plume settings → Updates** to install it.
- You can hide apps from the home row. Select **Plume settings → Apps in row**. Hidden apps stay in All apps.
- **App names** shows app names below the tiles: on focus, always or never.
- **Icon style → Colour** shows each logo in the colour of its brand.
- You can add wallpapers from your phone. Select **Add from phone…** in a wallpaper list, then scan the QR code.
- Fix: green areas no longer show in the sky of wallpapers.

## 1.1

- The APK is now 0.4 MB. It was 5.1 MB.
- The Gallery screensaver paintings are now a 4.7 MB download. Select **Plume settings → Screensaver → Gallery** to get them.
- Plume deletes the paintings when you select a different screensaver.
- Fix: Plume did not always find its Home button service.

## 1.0

- First release.
