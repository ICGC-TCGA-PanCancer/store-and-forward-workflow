# store-and-forward workflow

#### Initial setup on Amazon:
  - The image we are testing with currently is a m4.xlarge
  - The disk where work is done **must be encrypted**:
      - when launching the instance, add a volume and ensure "Encrypted" is selected
      - when setting up the workflow, we'll ensure the directories being used are located on that disk
  - Security rules used on this testing box: ssh from 206.108.127.0/24

#### Set up directory structure: work happens on the encrypted disk
```
sudo mkfs.ext4 /dev/xvdb
sudo mount /dev/xvdb /mnt

cd /mnt
sudo chmod 777 .
mkdir datastore
mkdir workflows

sudo ln -s /mnt/datastore /datastore
sudo ln -s /mnt/workflows /workflows
```  

#### Installing Docker on a worker
```
curl -sSL https://get.docker.com/ | sudo sh
sudo usermod -aG docker ubuntu
# log out then back in!
exit
```

#### Workflow dependencies
##### Our Git Order System
Progress is tracked by the workflow moving .json files to and from folders using Git. Each .json file represents a job. The workflow looks for jobs in the folder `queued-jobs` and performs git commands to move each job to subsequent other folders (and push changes to the repo) according success or failure of a given step for that job.
Further description of the Git Order System and the home of the tracking folders are at https://github.com/ICGC-TCGA-PanCancer/s3-transfer-operations

##### The Collaboratory CLI
The workflow uses the Collaboratory CLI to upload to the backend storage
https://github.com/CancerCollaboratory/cli

We store a store_and_forward.tar file in a locked down repo in Amazon to allow
access to github for moving JSON files, and to store the JKS file used by the 
collaboratory CLI tool.

Get these from the S3 bucket:

Install s3cmd and configure with your credentials (interactive).
```
sudo apt-get install s3cmd
s3cmd --configure
```
Download "store_and_forward.tar" and unpack
```
cd /home/ubuntu
s3cmd get s3://oicr.docker.private.images/store-and-forward.tar
mkdir /home/ubuntu/.gnos/
tar xvf store-and-forward.tar
mv /home/ubuntu/store-and-forward/* /home/ubuntu/.gnos/
```
Copy your gnos pem key to `/home/ubuntu/.gnos/gnos.pem`


#### Get the store-and-forward workflow
```
sudo apt-get install openjdk-7-jre-headless

cd /workflows
wget https://seqwaremaven.oicr.on.ca/artifactory/seqware-release/com/github/seqware/seqware-distribution/1.1.1/seqware-distribution-1.1.1-full.jar

s3cmd get s3://oicr.workflow.bundles/released-bundles/Workflow_Bundle_StoreAndForward_1.0.3_SeqWare_1.1.0.zip
java -cp seqware-distribution-1.1.1-full.jar net.sourceforge.seqware.pipeline.tools.UnZip --input-zip Workflow_Bundle_StoreAndForward_1.0.3_SeqWare_1.1.0.zip --output-dir /workflows/Workflow_Bundle_StoreAndForward_1.0.3_SeqWare_1.1.0
```

#### Run the workflow using docker
```
docker run -h master -it -v /var/run/docker.sock:/var/run/docker.sock -v /home/ubuntu/.gnos:/home/ubuntu/.gnos -v /datastore:/datastore -v /workflows:/workflows -v <your local ini file>:/workflow.ini seqware/seqware_whitestar_pancancer:1.1.1 bash -c "seqware bundle launch --ini /workflow.ini --dir /workflows/Workflow_Bundle_StoreAndForward_1.0.3_SeqWare_1.1.0/ --engine whitestar --no-metadata"
```

## On .ini files
###### Prepare an .ini file for the workflow
Get a copy of `template.ini` found at https://github.com/ICGC-TCGA-PanCancer/s3-transfer-operations/blob/master/scripts/template.ini and modify the field `collabToken` with the access token that Vitalii provided to you.

Generate the .ini file using `json2ini.py` found at https://github.com/ICGC-TCGA-PanCancer/s3-transfer-operations/blob/master/scripts/json2ini.py
The script requires pystache:
```
 sudo apt-get install python-pip
 sudo pip install pystache
```

###### Choose a job to test run
Select a .json file from https://github.com/ICGC-TCGA-PanCancer/s3-transfer-operations/tree/master/testing/queued-jobs
and use the script `json2ini.py` found at https://github.com/ICGC-TCGA-PanCancer/s3-transfer-operations/blob/master/scripts/json2ini.py then run:
`python json2ini.py [input json file] [template file] [output folder]`
