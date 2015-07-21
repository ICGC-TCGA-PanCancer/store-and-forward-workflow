# Building the Workflow

Instructions to setup a build environment for this workflow.

## How To Setup your Environment

  - Setup a fresh Ubuntu 14.04 image based machine in the environment of your choice.

  - Install the following packages:<br>
    ```sudo apt-get install openjdk-7-jdk maven```<br>

  - Install docker:<br>
    ```curl -sSL https://get.docker.com/ | sudo sh```<br>
    ```sudo usermod -aG docker ubuntu```<br>
    ```# log out then back in!```<br>

  - Clone the git repo:<br>
    ```mkdir ~/gitroot```<br>
    ```cd ~/gitroot```<br>
    ```it clone https://github.com/ICGC-TCGA-PanCancer/store-and-forward-workflow.git```<br>

## Building the Workflow

    cd /home/ubuntu/gitroot/store-and-forward-workflow
    mvn clean install

## Zipping up the workflow bundle

  - Mount the seqware whitestar docker container with the home folder in the same location<br>
    ```docker run -h master -it -v /home/ubuntu/:/home/ubuntu/ seqware/seqware_whitestar_pancancer:1.1.1 bash```<br>
    INSIDE THE CONTAINER:<br>
    ```cd /home/ubuntu/gitroot/store-and-forward-workflow```<br>
    ```seqware bundle package --dir target/Workflow_Bundle_StoreAndForward_1.0.2_SeqWare_1.1.0```<br>
    ```exit```<br>
    OUTSIDE THE CONTAINER:<br>
    ```cd /home/ubuntu/gitroot/store-and-forward-workflow```<br>
    ```s3cmd put Workflow_Bundle_StoreAndForward_1.0.2_SeqWare_1.1.0.zip s3://oicr.workflow.bundles/released-bundles```<br>
    
