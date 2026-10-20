# Add-ons

An add-on is a feature built into the app that the portal switches on for a profile. There are no
downloadable plugins: the code ships in the APK and stays off until the portal says otherwise. The
portal is the only authority, and the app never keeps a "paid" flag of its own.

## Adding one

1. **Pick an id**: 2 to 40 characters of `a-z`, `0-9`, `-` and `_`, starting with a letter or digit
   (for example `kids-games`). It can't change later, and the Rust and Apple apps must use the same id.
2. **Build the feature behind the gate** (Android):
   - Add the id to `KNOWN` in `android/app/src/main/java/app/familytv/addons/Addons.kt`.
   - Run the feature only while `Addons.isActive(app.portal, "kids-games")` is true. Check it where
     the feature starts, not once at app start, because the portal can pause or revoke it.
   - Test with `Addons.isActive(id, config, contactAt, now, known)`, which takes everything as
     arguments (see `AddonsTest`).

   The gate is on only when the id is in `KNOWN`, the portal sends the state `active`, the
   profile's `membershipUntil` hasn't passed, and the portal answered this TV in the last 7 days.
3. **Ship it through a PR to `main`.** The release workflow publishes the APK once it merges.
4. **Create the add-on on the portal** (admin page, Add-ons tab): id, name (up to 60 characters),
   description (up to 300) and status. `draft` stays hidden from TVs, `available` is listed as
   "Buy on the portal", and `retired` takes no new buyers while profiles that own it keep it.
   Saving one that isn't a draft tells every paired TV to reload.
5. **Grant it to a profile** with the Grant button in the add-on's profile list (it reads Revoke
   once owned). The profile's TVs reload at their next check-in (every 30 seconds while the app is
   open), and Settings > Add-ons shows "On". If the profile's membership end date has passed, it
   shows "Paused: renew your subscription" until the date is extended.

The portal needs no code change for a new add-on. If the feature calls an outside service, its key
lives in the portal as a Worker secret behind a portal endpoint that checks the device token and
that the add-on is active for the TV's profile; the app only ever calls the portal.

## Not accepted as add-ons

Real-Debrid, Torrentio and Stremio-style streams, stream proxying, and anything that puts IPTV
logins, keys or stream URLs in code, logs or files.
