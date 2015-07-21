# Building the Workflow

Instructions to setup a build environment for this workflow.

## Dependencies
  - Setup a fresh Ubuntu 14.04 image based machine in the environment of your choice.
  - Install the following packages:
    ```sudo apt-get install openjdk-7-jdk maven```
  - Install docker:
    ```curl -sSL https://get.docker.com/ | sudo sh
       sudo usermod -aG docker ubuntu
       # log out then back in!
       exit```
