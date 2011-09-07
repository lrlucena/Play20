@echo off 

set cwd=%~dp0
set args=%*

IF "%cwd:~-1%"=="\" SET cwd=%cwd:~0,-1%

SET cwd=%cwd:\=/%

"%JAVA_HOME%\bin\java" -Xms512M -Dfile.encoding=UTF8 -Xmx1536M -Xss1M -XX:+CMSClassUnloadingEnabled -XX:MaxPermSize=256m -Dplay.home=%cwd% -Dsbt.boot.properties=sbt.boot.properties -jar %cwd%/sbt/sbt-launch-0.10.1.jar %args%