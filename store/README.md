# Play Store listing

Graphics for LibreChat's store listing, matching Play Console's **Main store listing → Graphics**:

| File or folder | Play Console field | Size |
|---|---|---|
| `LibreChat icon.png` | App icon | 512 × 512 (Play rounds the corners) |
| `LibreChat feature graphic.png` | Feature graphic | 1024 × 500 |
| `phone/` | Phone screenshots | 1440 × 2560 |
| `tablet-7/` | 7-inch tablet screenshots | 1440 × 2560 |
| `tablet-10/` | 10-inch tablet screenshots | 2560 × 1440 |
| `chromebook/` | Chromebook screenshots | 2560 × 1440 |

Screenshots are named for their app and device, and numbered in the order to upload them. The screens inside the frames are drawn by the app's
own code (`ScreensTest`, which also renders the tablet sizes into `app/build/screens/`).

## Videos

- `videos/LibreChat FOREGROUND_SERVICE_CONNECTED_DEVICE.mp4`: the connected device foreground service declaration (the mesh service).
- `videos/LibreChat promo.mp4`: the promo video for the store listing (Main store listing → Video), with original music. The people and chats in it are made up, drawn by the app's own screens from `PromoScreensTest`.
