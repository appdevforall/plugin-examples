# PCF Template Installer Plugin

A dedicated plugin for the **Code on the Go** mobile app designed to streamline the creation, implementation, and testing of *Pebble Custom Functions* (PCF). 
by allowing developers to quickly create Pebble Custom Function and test the new function by providing two (2) new templates in **Code On The Go**.

---

## 🛠 Features
* **One-Tap Installation:** Installs standard PCF templates directly into your Code On The Go project directory.
* **Dependency Management:** Automatically ensures that required libraries for Pebble Custom Functions are correctly referenced.
* **Mobile Optimized:** Built specifically for the "Code on the Go" interface for a smooth developer experience on Android.

## 📦 Installation

### Prerequisites
* **Code on the Go** IDE installed on your Android device.

### Steps
1.  Open the **Code on the Go** app.
2.  Navigate to the **Plugin Manager** in **Preferences** from the main menu.
3.  Select **Install from URL**  using the download icon in the upper right corner of the screen
      or search for `PCFTemplateInstallerPlugin` using the **'+'** icon in the lower right corner of the screen.
4.  Restart the IDE to activate the plugin.

## 🚀 How to Use
After **Code On The Go** has restarted two new templates will appear on the *New Project Screen*. They are:
1. *PCFBuilder* This template will create a project to create the jar file (*extensions.jar*) which
   contain the executable code for one or more *Pebble Custom Functions*. Each function must be in
   a sperate file with a class name that will be used to invoke the code from Pebble. If the *Include ExampleCode*
   is selected then four (4) examples of Pebble Custom Functions will be included in the project. There is a tutorial for
   Creating Pebble Custom Functions which goes into further detail on how to create a Pebble Custom Function.
3. *PCFExample* This template will create a project which uses the example Pebble Customs created in the *PCFBuilder* project.
   
## 🤝 Contributing
We welcome contributions to expand the available PCF templates!
Please visit our website *appdevforall.org* to see how you can share your plugins with the Code On the Go community

## Building

From a checkout of this folder (with `libs/plugin-api.jar` and `libs/gradle-plugin.jar` in place and `local.properties` pointing at an Android SDK):

```sh
./gradlew assemblePlugin
```

The installable artifact is written to `build/plugin/pcfinstaller.cgp`.

---

### About App Dev For All
We are dedicated to making mobile development accessible to everyone. Check out our other tools and resources at [appdevforall.org](https://www.appdevforall.org).

