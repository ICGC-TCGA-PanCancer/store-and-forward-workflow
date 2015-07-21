# Building the Workflow

Instructions to setup a build environment for this workflow.

## Dependencies
  - Setup a fresh Ubuntu 14.04 image based machine in the environment of your choice.
  - Install the following packages:<br>
    ```sudo apt-get install openjdk-7-jdk maven```<br>
  - Install docker:<br>
    ```curl -sSL https://get.docker.com/ | sudo sh```<br>
    ```sudo usermod -aG docker ubuntu```<br>
    ```# log out then back in!```<br>
    ```exit```<br>
