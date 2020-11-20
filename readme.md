# PiPup

PiPup is an application that allows displaying user-defined custom notifications on Android TV.

The most common use-case for this application is for sending notifications, from a home-automation solution, to your Android TV.

![](https://github.com/rogro82/PiPup/raw/master/graphics/screenshot-1.png)

__Some example scenarios:__

- Show a snapshot of your camera on your TV (eg on a motion trigger)
- Display a notification with the video of your camera when someone is at your door
- Send a notification when your dryer/washingmachine is ready
- Anything else you might find useful


__The application is currently in a `public beta`__

To enter the `beta` and install the application on your device go to:  
https://play.google.com/apps/testing/nl.rogro82.pipup

_Important: after installation / updating it is currently advised to restart your TV and open the application once to make sure the background-service is running_

#### Sideloading:

On Android TV (8.0+), when sideloading, you will need to set the permission for SYSTEM_ALERT_WINDOW manually (using adb) as there is no interface on Android TV to do this.

To give the application the required permission to draw overlays you will need to run:
```
adb shell appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow
```

## Integrating

PiPup uses an embedded webserver (NanoHTTPD) which runs on port 7979.

### Sending notifications

#### To send notifications with an external media resource (image, url or webview) use application/json


| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /notify          |
| Method:       | POST             |
| Content-Type: | application/json |

Example json data:

```json
{
  "duration": 30,
  "position": 0,
  "title": "Your awesome title",
  "titleColor": "#0066cc",
  "titleSize": 20,
  "message": "What ever you want to say... do it here...",
  "messageColor": "#000000",
  "messageSize": 14,
  "backgroundColor": "#ffffff",
  "media": { "image": {
    "uri": "https://mir-s3-cdn-cf.behance.net/project_modules/max_1200/cfcc3137009463.5731d08bd66a1.png", "width": 480
  }}
}
```
All fields are optional and for `media` you can specify 3 types:

```json 
{ "image": { "uri": "address_to_your_image", "width": 480 }}
{ "video": { "uri": "address_to_your_video", "width": 480 }}
{ "web":   { "uri": "address_to_your_resource", "width": 640, "height": 480 }}
```

#### To send notifications with an image file use multipart/form-data

| Property      | Value               |
| ------------- | ------------------- |
| Path:         | /notify             |
| Method:       | POST                |
| Content-Type: | multipart/form-data |

Form-fields:

| Field           | Type                                         |
| --------------- | -------------------------------------------- |
| duration        | Integer (default=30)                         |
| position        | Integer (0..4, default=0)                    |
| title           | String                                       |
| titleSize       | Integer (default=16)                         |
| titleColor      | string (default=#FFFFFF, format=[AA]RRGGBB   |
| message         | String                                       |
| messageSize     | Integer (default=12)                         |
| messageColor    | String (default=#FFFFFF, format=[AA]RRGGBB   |
| backgroundColor | String (default=#CC000000, format=[AA]RRGGBB |
| image           | File                                         |
| imageWidth      | Integer (default=480)                        |

`position` is an enum ranging from 0 to 4

|  | Position    |
| -----: | ----------- |
| 0     | TopRight    |
| 1     | TopLeft     |
| 2     | BottomRight |
| 3     | BottomLeft  |
| 4     | Center      |

Color-properties are in `[AA]RRGGBB` where the alpha channel is optional e.g. #FFFFFF or #CCFFFFFF
