# Android App for Facial Landmark Localization
An Android Application capable of localizing a set of facial landmarks trough the frontal camera of the device with pretty-good detection performance.

## Requires:
* OpenCV 4
* Dlib
* Kotlin support
* Kotlin Coroutines

## Workflow
- The app utilize the frontal camera of the device to continously capture frames that are directly processed by the Face Detector built inside the Camera1 API's.
- From the detected faces only the prominent one is taken and further analized.
- The face is then converted to grayscale (OpenCV) and processed by the Dlib Face Landmark Detector.
- Finally, the localized landmarks are drawn on the UI and the entire process will repeat for the next captured frame.

## Models
The models used by the Dlib landmark detector can be downloaded directly inside the app, otherwise they can be found [here](https://github.com/davisking/dlib-models) and [here](https://github.com/Luca96/dlib-minified-models/tree/master/face_landmarks).

## Usage:
1. First `clone` the repository.
2. Before importing the project into AndroidStudio there's a __little editing__ to do:
   * Open the `app/CMakeLists.txt`
   * Then, __replace__ with your path the variables **PROJECT_PATH** and **OPENCV_PATH**.
3. Now the project is ready to be imported into AndroidStudio.

## Building Dlib from Scratch
If you want to build the latest Dlib release with custom optimization and ABIs, you can follow the instructions available [here](https://github.com/Luca96/dlib-for-android). Otherwise, you can continue with the ones that I'd already prebuilt. 
