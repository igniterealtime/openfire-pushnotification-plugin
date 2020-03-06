call mvn clean package

cd target
rename pushnotification-openfire-plugin-assembly.jar pushnotification.jar
copy pushnotification.jar C:\openfire_4_5_0\plugins\pushnotification.jar
pause