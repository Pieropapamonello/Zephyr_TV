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

## 2.6

- The TV channel cards show the program that is on now, after the channel number. The bar under the card shows how far into the program you are. The guide comes from the TV.
- Channels that the TV lists two times show one time only.
- More channels get their logo. Piume finds names like "TELEAMBIENTE" for "TeleAmbiente TV" and "Mediaset Italia2" for "Italia 2". It also uses the logos that iptv-org has only as SVG on Wikimedia.
- When you go back from an app, the home screen shows where you left it. The Home key still goes back to the start of the apps row.

## 2.5.1

- The blurred background of the channel cards and of the search results is smoother.

## 2.5

- The TV channel cards show the logo of the channel, large in the middle on a blur of itself. The name and the number of the channel are under the card.
- When the TV has no logo for a channel, Piume gets it from iptv-org, a public list of TV channels. Piume downloads the list for your country the first time and then each month.
- To turn this off, select **Piume settings → Continue Watching → Channel logos (iptv-org)**. To get the latest logos now, select **Update the channel logos**.

## 2.4.13

- Search results without a picture on TMDB now show the poster or the name of the title. Before, their card was empty.

## 2.4.12

- Every search result fills its card with a picture. When TMDB has no wide picture, the card shows the poster on a blur of itself. When TMDB has no picture at all, the card shows the logo or the name of the title on a colour.
- The arrows of the rows are level with the middle of the tiles and the cards.

## 2.4.11

- The arrows at the sides of the rows and of Spotlight are smaller.
- When you go down from the apps row to the recommendations, the arrows of the apps row go away.
- The Piume settings pages show only the arrow at the bottom, while there are more items below. The arrow at the top is gone.

## 2.4.10

- In Piume settings, each section shows an arrow instead of a value. The arrow tells you that the section opens a page.

## 2.4.9

- Arrows show at the sides of the apps row and of the recommendation rows when there are more items that way. They show only on the row that has the focus.
- The Spotlight arrows show only when Spotlight has the focus.
- An arrow shows at the bottom of the Piume settings pages when the page has more items below.
- In the Glass theme, the edges of the cards and of the Spotlight buttons are thinner and softer.
- In the Glass theme, the side page (Piume settings, search, the page of a result) is smoked glass over the home screen.
- On the page of a search result, the first item stays in full view when you go back up to it.

## 2.4.8

- On the page of a search result, the description shows again when you go back up to the first item.
- The description lines up with the title of the page.

## 2.4.7

- The cover on the page of a search result now reaches the edges of the page and the top of the screen.

## 2.4.6

- On the page of a search result, the cover is at the very top, above the title. It fills the full width of the page.

## 2.4.5

- The page of a search result shows its wide cover at the top, with the text under it.
- Each app on that page shows its icon next to its name.

## 2.4.4

- Search results are wide covers with the title on them, in the language that you set for TMDB, or else in English. When TMDB has no such cover, the card shows the logo of the title on the picture.
- Pictures in the recommendations, like the YouTube ones, fill the card. They have no black bars.

## 2.4.3

- **YouTube trailer** is the first button in Spotlight and the first item on the page of a search result.

## 2.4.2

- When the Spotlight title has the focus, a soft band goes across the full screen behind it. It has no border.
- The Spotlight description uses all the width of the screen.

## 2.4.1

- The keyboard closes when you search, and when you close a page of Piume settings.

## 2.4

- Search results no longer replace Spotlight. A **Results** tile shows after **Continue Watching**, with the results as cards under the apps row.
- Press OK on a result to see its description, the YouTube trailer and the apps that have it.
- Press Back, or hold OK on the **Results** tile, to close the search.
- **Say it** listens in the search page. The words show in the search box while you speak. The first time, allow Piume to use the microphone.
- In the search page, Down goes from the search box to **Say it**.
- The Spotlight title shows a soft panel when it has the focus.
- The pages that open on your phone from a QR code show the name of the TV at the bottom.

## 2.3

- The top bar has a **Search** button with a magnifying glass. Type a title with the remote, say it, or type it on your phone with the QR code.
- Search finds films and series on TMDB. Spotlight shows the results, with the description, the trailer and the apps that have the title. Press Back to close the search.
- Hold OK on **Search** to open the voice assistant of the TV.
- When you move along the apps row, empty cards show while the recommendations of the app load. The cards of the previous app do not stay on the screen.
- From the apps row, Up goes to the Spotlight buttons first, then to the title, then to the top bar.

## 2.2

- The new **Glass** theme shows the wallpaper through glossy glass cards. The text is white and easy to read on any photo. Select **Piume settings → Appearance & wallpapers → Theme → Glass**. It uses the dark wallpaper.

## 2.1

- In Piume settings, a long value no longer cuts the name of the setting. The value is shorter instead.
- Each row that an app shows on the home screen is now a section under the apps row. For example, YouTube shows **Recommended** and **Trending** as two rows. Move down to see the next section.
- To select the sections of an app, hold OK on the app and select **Visible sections**.
- The TV app shows all the channels of the TV, from channel 1, in number order. Each tuner is a section. All channel cards have the same design on all TVs.
- On a TV without a tuner, the TV app shows no channels.
- Spotlight does not change the title while the title or its buttons have the focus.
- Press OK on the Spotlight title to show all of a long description. Press OK again to make it short.
- The Spotlight title is a little higher. The space above it and the space below it are the same.
- The first and the last item of a row now show all of their shadow when they have the focus.

## 2.0

- The Spotlight image is clearer. It has a fade only under the apps row. The Spotlight text has no panel behind it. It has the same soft shadow as the clock.
- Spotlight can show your own list from themoviedb.org instead of the trending titles. Make a public list there, then select **Piume settings → TMDB Spotlight → My TMDB list** and paste its link on your phone.
- The Gallery screensaver can show the Spotlight trending images, with the title, the year and the genre. Select **Piume settings → Screensaver → Screensaver → Spotlight trending (TMDB)**.
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
