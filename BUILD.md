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
    ```cd /home/ubuntu/gitroot/store-and-forward-workflow```<br>
    ```mvn clean install```<br>

## Zipping up the workflow bundle
    ```docker run -h master -it -v /var/run/docker.sock:/var/run/docker.sock -v /home/ubuntu/gitroot:/workflows seqware/seqware_whitestar_pancancer:1.1.1 bash -c "seqware bundle package --dir /workflows/store-and-forward-workflow/target/Workflow_Bundle_StoreAndForward_1.0.2_SeqWare_1.1.0 --to /workflows "```
    ```s3cmd put Workflow_Bundle_StoreAndForward_1.0.2_SeqWare_1.1.0.zip s3://oicr.workflow.bundles/released-bundles```
    
