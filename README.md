Akka Hands-on course
====================

Installation:
-------------
0. Install the "Traiana Libs" ZIP locally (follow the `instructions.txt` file in the ZIP).
1. Clone this repo.
2. Install IntelliJ (community edition is fine).
3. Install IntelliJ Scala plugin.
4. Install IntelliJ Gradle plugin.
5. Open the main build.gradle file.
6. Run the generateProto Gradle task on the project (from command line or the Gradle tool window).
   - This may fail if you don't have Python 2.7 installed
7. Build the project. IntelliJ might prompt you to select a Scala SDK. If it does, select the option to get it from
   Maven, and download Scala 2.12.4.
8. Run the `Nagger` Server and `NaggerClient`. This won't do much, but getting this far means we have successfully
   downloaded the entire Internet and don't have to worry about WiFi acting up...
   
Further in the course we will also need:
1. Install Docker.
2. In a terminal, execute `docker pull cassandra:3.9`.
