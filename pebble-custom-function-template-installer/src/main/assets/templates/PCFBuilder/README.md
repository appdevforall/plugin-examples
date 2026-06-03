# HOWTO build 

# Benefit

Pebble is a template expansion library. Typically it would substitute text for certain placeholder values. Custom funtions made these templates more powerful and flexible.

# Output

A file `extensions.jar` containing executable code that will be loaded later by the Pebble engine.

# Steps
1. Wait until the initial setup is complete
2. Click on Gradle Tasks icon in the project action toolbar near the top of the app
3. Search for ":ext:buildExtensionsJar"
4. Select the checkbox for ":ext:buildExtensionsJar"
5. Click the green triangle in the upper right corner
6. Click the green triangle in the upper right corner a second time to confirm
7. Wait for the build to complete
8. Use the project menu button (3 horizontal lines) in the upper left corner to open the file tree
9. Navigate through the "ext" folder, and the "build" folder" to arrive at the "libs" folder
  If you don't see those folders, then close and re-open the file tree to refresh the listings
  You should see a new file "extensions.jar" in the "libs" folder - it was produced by the job you ran earlier
10. Long-press on "extensions.jar" to perform a "copy path" operation
11. Use Termux to copy the "extensions.jar" file to /sdcard/Download

Congratualtions! You now have a jar library of pebble custom functions that can be included with a Pebble template elsewhere.
