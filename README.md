# Android App for Facial Landmark Localization
An Android Application capable of localizing a set of facial landmarks trough the frontal camera of the device with pretty-good detection performance.

## Requires:
* OpenCV
* Dlib
* Kotlin Coroutines

## Workflow
- The app utilize the frontal camera of the device to continously capture frames that are directly processed by the Face Detector built inside the Camera1 API's.
- From the detected faces only the prominent one is taken and further analized.
- The face is then converted to grayscale (OpenCV) and processed by the Dlib Face Landmark Detector.
- Finally, the localized landmarks are drawn on the UI and the entire process will repeat for the next captured frame.

## Models
The models used by the Dlib landmark detector can be downloaded directly inside the app, otherwise they can be found [here](https://github.com/davisking/dlib-models) and [here](https://github.com/Luca96/dlib-minified-models/tree/master/face_landmarks).