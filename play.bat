@echo off 

set cwd=%~dp0
set args=%*

if exist "conf/application.yml" (
	%cwd%framework\build.bat play %args%
) else (
	"%JAVA_HOME%\bin\java" -cp %cwd%framework/sbt/boot/scala-2.9.0/lib/*;%cwd%framework/sbt/boot/scala-2.9.0/org.scala-tools.sbt/sbt/0.10.1/*;%cwd%repository/play/play_2.9.0/2.0/jars/* play.console.Console %args%
)