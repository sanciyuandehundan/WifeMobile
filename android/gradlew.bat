@rem Gradle wrapper for Windows: runs gradle\wrapper\gradle-wrapper.jar with JAVA_HOME's java.
@echo off
set DIRNAME=%~dp0
"%JAVA_HOME%\bin\java.exe" -Dorg.gradle.appname=gradlew -classpath "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
