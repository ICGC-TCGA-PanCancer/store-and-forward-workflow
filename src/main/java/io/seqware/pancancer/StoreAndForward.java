package io.seqware.pancancer;

import com.google.common.collect.Lists;
import java.util.ArrayList;
import net.sourceforge.seqware.pipeline.workflowV2.AbstractWorkflowDataModel;
import net.sourceforge.seqware.pipeline.workflowV2.model.Job;

public class StoreAndForward extends AbstractWorkflowDataModel {

		// Common Variables
    private static final String SHARED_WORKSPACE = "shared_workspace";
    public static final int DEFAULT_GNOS_TIMEOUT_MIN = 20;
    public static final int DEFAULT_GNOS_RETRIES = 3;
    private ArrayList<String> analysisIds = null;
    private ArrayList<String> downloadUrls = null;
    private ArrayList<String> downloadMetadataUrls = null;
    private String gnosServer = null;
    private String pemFile = null;
    // Booleans for sequence control
    private Boolean skipdownload = false;
    private Boolean skipupload = false;
    private Boolean cleanup = true;
    // JSON Configuration
    private String jsonRepo = null;
    private String jsonLocation = "/datastore/gitroot";
    private String jsonRepoName = "s3-transfer-operations";
    private String jsonFolderName = null;
    private String jsonFileName = null;
    private String jsonXmlHash = null;
    // Git Configuration
    private String gitEmail = "nbyrne.oicr@gmail.com";
    private String gitName = "ICGC AUTOMATION";
    private String gitPemFile = null;
    // Collaboratory Tool
    private String collabToken = null;
    private String collabCertPath = null;
    private String collabHost = null;
    // Collab Tool Logging
    private String collabS3Cfg = null;
    private String collabLogBucket = null;
    // Docker Config
    private String gnosDockerName = null;
    private String collabDockerName = null;
    // Gnos Timing
    private int gnosTimeoutMin = DEFAULT_GNOS_TIMEOUT_MIN;
    private int gnosRetries = DEFAULT_GNOS_RETRIES;
    private Float gnosMinimumDownloadRate = null;

    @Override
    public void setupWorkflow() {
        try {
          
            // identify Content
            this.analysisIds = Lists.newArrayList(getProperty("analysisIds").split(","));
	    
            // GNOS DOWNLOAD:
            
            // This may end up being a list of servers, just take the first element for now
            this.gnosServer = getProperty("gnosServers").split(",")[0];
            
            this.pemFile = getProperty("pemFile");
            this.downloadMetadataUrls = Lists.newArrayList();
            this.downloadUrls = Lists.newArrayList();
            for (String id : Lists.newArrayList(getProperty("analysisIds").split(","))) {
            	StringBuilder downloadMetadataURLBuilder = new StringBuilder();
            	StringBuilder downloadDataURLBuilder = new StringBuilder();
            	downloadMetadataURLBuilder.append(gnosServer).append("/cghub/metadata/analysisFull/").append(id);
            	downloadDataURLBuilder.append(gnosServer).append("/cghub/data/analysis/download/").append(id);
            	this.downloadUrls.add(downloadDataURLBuilder.toString());
            	this.downloadMetadataUrls.add(downloadMetadataURLBuilder.toString());
            }
            
            // Collab Tool
            this.collabToken = getProperty("collabToken");
            this.collabCertPath = getProperty("collabCertPath");
            this.collabHost = getProperty("collabHost");
            this.collabLogBucket = getProperty("collabLogBucket");
            this.collabS3Cfg = getProperty("collabS3Cfg");
            
            // Docker Config
            this.gnosDockerName = getProperty("gnosDockerName");
            this.collabDockerName = getProperty("collabDockerName");
            
            // JSON Git Repo
            this.jsonRepo = getProperty("JSONrepo");
            this.jsonFolderName = getProperty("JSONfolderName");
            this.jsonFileName = getProperty("JSONfileName");
            this.jsonXmlHash = getProperty("JSONxmlHash");
            this.gitEmail = getProperty("GITemail");
            this.gitName = getProperty("GITname");
            this.gitPemFile = getProperty("GITPemFile");

            // GNOS timeouts
            if(hasPropertyAndNotNull("gnosTimeoutMin")) {
		            this.gnosTimeoutMin = Integer.parseInt(getProperty("gnosTimeoutMin"));
            }
            if(hasPropertyAndNotNull("gnosRetries")) {
		            this.gnosRetries = Integer.parseInt(getProperty("gnosRetries"));
            }
            if(hasPropertyAndNotNull("gnosMinimumDownloadRateMB")){
		            this.gnosMinimumDownloadRate = Float.parseFloat(getProperty("gnosMinimumDownloadRateMB"));
            }
	    
		    // skipping
	        if(hasPropertyAndNotNull("skipdownload")) {
	        	this.skipdownload = Boolean.valueOf(getProperty("skipdownload").toLowerCase());
	        }
	        if(hasPropertyAndNotNull("skipupload")) {
	        	this.skipupload = Boolean.valueOf(getProperty("skipupload").toLowerCase());
	        }
	        
		    // cleanup
	        if(hasPropertyAndNotNull("cleanup")) {
	        	this.cleanup = Boolean.valueOf(getProperty("cleanup"));
	        }
	    
	        } catch (Exception e) {
	            throw new RuntimeException("Could not read property from ini", e);
	        }
    }
    
    /*
     MAIN WORKFLOW METHOD
    */

    @Override
    /**
     * The core of the overall workflow
     */
    public void buildWorkflow() {

        // create a shared directory in /datastore on the host in order to download reference data
        Job createSharedWorkSpaceJob = createDirectoriesJob();
        
        // Install Dependencies for Ubuntu
        Job installDependenciesJob = pullRepo(createSharedWorkSpaceJob);
        
        // Move the JSON file to download
        Job move2download = gitMove(installDependenciesJob, "queued-jobs", "downloading-jobs");
        
        // download data from GNOS
        Job getGNOSJob = createGNOSJob(move2download);
        
        // Move the JSON file to verify
        Job move2verify = gitMove(getGNOSJob, "downloading-jobs", "verification-jobs");   
        
        // download verification
        Job verifyDownload = createVerifyJob(move2verify);
        
        // Move the JSON file to upload
        Job move2upload = gitMove(verifyDownload, "verification-jobs", "uploading-jobs");
        
        // upload data to S3
        Job s3Upload = s3ToolJob(move2upload);
	
        // Move the JSON file to finished
        Job move2finished = gitMove(s3Upload, "uploading-jobs", "completed-jobs");
        
        // Calculate Timing Information and Move into Git
        Job timing = gitTiming(move2finished);
        
        // now cleanup
        cleanupWorkflow(timing);
        
    }
    
    /*
     JOB BUILDING METHODS
    */
    
    private Job gitMove(Job lastJob, String src, String dst) {
    	Job manageGit = this.getWorkflow().createBashJob("git_manage_" + src + "_" + dst);
    	String path = this.jsonLocation + "/" +  this.jsonRepoName + "/" + this.jsonFolderName;
    	manageGit.getCommand().addArgument("git config --global user.name " + this.gitName + " \n");
    	manageGit.getCommand().addArgument("git config --global user.email " + this.gitEmail + " \n");
    	manageGit.getCommand().addArgument("if [[ ! -d " + path + " ]]; then mkdir -p " + path + "; fi \n");
    	manageGit.getCommand().addArgument("cd " + path + " \n");
    	manageGit.getCommand().addArgument("# This is not idempotent: git pull \n");
    	manageGit.getCommand().addArgument("git reset --hard origin/master \n");
    	manageGit.getCommand().addArgument("git pull \n");
    	manageGit.getCommand().addArgument("if [[ ! -d " + dst + " ]]; then mkdir " + dst + "; fi \n");
    	manageGit.getCommand().addArgument("if [[ -d " + src + " ]]; then git mv " + path + "/" + src + "/" + this.jsonFileName + " " + path + "/" + dst + "; fi \n");
    	manageGit.getCommand().addArgument("git stage . \n");
    	manageGit.getCommand().addArgument("git commit -m '" + dst + ": " + this.jsonFileName +"' \n");
    	manageGit.getCommand().addArgument("git push \n");
    	manageGit.getCommand().addArgument("sleep $(bc <<< \"scale=5; $RANDOM / 32767 * 10\"  ) \n");
    	manageGit.addParent(lastJob);
    	return manageGit;
    }
    
    private Job gitTiming(Job lastJob) {
    	Job consolidateTiming = this.getWorkflow().createBashJob("git_timing");
    	consolidateTiming.getCommand().addArgument("cd " + SHARED_WORKSPACE + " \n");
    	String path = this.jsonLocation + "/" +  this.jsonRepoName + "/timing-information";
    	consolidateTiming.getCommand().addArgument("if [[ ! -d " + path + " ]]; then mkdir -p " + path + "; fi \n");
        assert(this.downloadUrls.size() == this.analysisIds.size());
    	for (int index = 0; index < this.downloadUrls.size(); index++) {
    		consolidateTiming.getCommand().addArgument("sharedpath=`pwd` \n");
	    	consolidateTiming.getCommand().addArgument("python " + this.getWorkflowBaseDir() + "/scripts/timing.py " + this.analysisIds.get(index) +" \n");
	    	consolidateTiming.getCommand().addArgument("cd " + path + " \n");
	    	consolidateTiming.getCommand().addArgument("git reset --hard origin/master \n");
	    	consolidateTiming.getCommand().addArgument("git pull \n");
	    	consolidateTiming.getCommand().addArgument("mv ${sharedpath}/" + this.analysisIds.get(index) + ".timing " + path + "/" + this.jsonFileName
				    + ".timing \n");
	    	consolidateTiming.getCommand().addArgument("git stage . \n");
	    	consolidateTiming.getCommand().addArgument("git commit -m 'Timing for: " + this.analysisIds.get(index) + "' \n");
	    	consolidateTiming.getCommand().addArgument("git push \n");
    	}
    	consolidateTiming.addParent(lastJob);
    	return consolidateTiming;
    }
    
    private void cleanupWorkflow(Job lastJob) {
        if (cleanup) {
          Job cleanup = this.getWorkflow().createBashJob("cleanup");
          cleanup.getCommand().addArgument("cd " + SHARED_WORKSPACE + " \n");
          cleanup.getCommand().addArgument("rm -rf downloads\\* \n");
          cleanup.addParent(lastJob);
        } 
    }
    
    private Job createDirectoriesJob() {
		Job createSharedWorkSpaceJob = this.getWorkflow().createBashJob("create_dirs");
		createSharedWorkSpaceJob.getCommand().addArgument("mkdir -m 0777 -p " + SHARED_WORKSPACE + "/downloads \n");
		createSharedWorkSpaceJob.getCommand().addArgument("cd " + SHARED_WORKSPACE + " \n");
		createSharedWorkSpaceJob.getCommand().addArgument("date +%s > workflow_timing.txt \n");
		return createSharedWorkSpaceJob;
    }

    private Job pullRepo(Job getReferenceDataJob) {
    	Job installerJob = this.getWorkflow().createBashJob("install_dependencies");
    	installerJob.getCommand().addArgument("if [[ ! -d ~/.ssh/ ]]; then  mkdir ~/.ssh; fi \n");
    	installerJob.getCommand().addArgument("cp " + this.gitPemFile + " ~/.ssh/id_rsa \n");
    	installerJob.getCommand().addArgument("chmod 600 ~/.ssh/id_rsa \n");
    	installerJob.getCommand().addArgument("echo 'StrictHostKeyChecking no' > ~/.ssh/config \n");
    	installerJob.getCommand().addArgument("if [[ -d " + this.jsonLocation + " ]]; then  exit 0; fi \n");
    	installerJob.getCommand().addArgument("mkdir -p " + this.jsonLocation + " \n");
    	installerJob.getCommand().addArgument("cd " + this.jsonLocation + " \n");
    	installerJob.getCommand().addArgument("git config --global user.name " + this.gitName + " \n");
    	installerJob.getCommand().addArgument("git config --global user.email " + this.gitEmail + " \n");
    	installerJob.getCommand().addArgument("git clone " + this.jsonRepo + " \n");
    	installerJob.addParent(getReferenceDataJob);
    	return installerJob;
    }
    
    private Job createGNOSJob(Job getReferenceDataJob) {
	  Job gnosJob = this.getWorkflow().createBashJob("GNOS_download");
	  if (this.skipdownload) {
		  gnosJob.getCommand().addArgument("# You have skip download enabled in your ini file.  \n");
		  gnosJob.getCommand().addArgument("exit 0 \n");
	  }
	  gnosJob.getCommand().addArgument("cd " + SHARED_WORKSPACE + "/downloads \n");
	  int index = 0;
	  gnosJob.getCommand().addArgument("date +%s > ../download_timing.txt \n");
      assert(this.downloadUrls.size() == this.downloadMetadataUrls.size() && this.downloadUrls.size() == this.analysisIds.size());
	  for (String url : this.downloadUrls) {
		  // Add GNOS tag for download
		  String server = this.gnosServer.replace("http://", "");
		  server = server.replace("https://", "");
		  server = server.replace("/", "");
		  server = server.replace("gtrepo-", "");
		  server = server.replace(".", "-");
		  gnosJob.getCommand().addArgument("echo -n \"" + server + "\" > /datastore/gnos_id.txt \n");
		  gnosJob.getCommand().addArgument("python " + this.getWorkflowBaseDir() + "/scripts/md5_check.py " + this.downloadMetadataUrls.get(index) + " " + this.jsonXmlHash
				  + " \n");
		  gnosJob.getCommand().addArgument("mv patched.xml " + this.analysisIds.get(index) + ".xml \n");
		  gnosJob.getCommand().addArgument("echo '" + url + "' > individual_download_timing.txt \n");
		  gnosJob.getCommand().addArgument("date +%s > individual_download_timing.txt \n");
		  gnosJob.getCommand().addArgument("docker run "
					      // link in the input directory
					      + "-v `pwd`:/workflow_data "
					      // link in the pem key
					      + "-v "
					      + this.pemFile
					      + ":/gnos_icgc_keyfile.pem " + this.gnosDockerName
					      // here is the Bash command to be run
					      + " /bin/bash -c \"cd /workflow_data/ && perl -I /opt/gt-download-upload-wrapper/gt-download-upload-wrapper-2.0.13/lib "
					      + "/opt/vcf-uploader/vcf-uploader-2.0.7/gnos_download_file.pl "
					      + "--url " + url + " . "
					      + " --retries " + this.gnosRetries + " --timeout-min " + this.gnosTimeoutMin + " "
					      + " --file /gnos_icgc_keyfile.pem "
				          + (this.gnosMinimumDownloadRate != null ? "--min-mbyte-download-rate " + this.gnosMinimumDownloadRate:"")
					      + " --pem /gnos_icgc_keyfile.pem\" \n");
		  gnosJob.getCommand().addArgument("sudo chown -R seqware:seqware " + this.analysisIds.get(index) + " \n");
		  gnosJob.getCommand().addArgument("date +%s >> individual_download_timing.txt \n");
		  index += 1;
	  }
	  gnosJob.getCommand().addArgument("date +%s >> ../download_timing.txt \n");
	  gnosJob.getCommand().addArgument("du -c . | grep total | awk '{ print $1 }' > ../download.size \n");
	  gnosJob.getCommand().addArgument("cd - \n");
	  gnosJob.addParent(getReferenceDataJob);
	  return gnosJob;
    }
    
    private Job s3ToolJob(Job getReferenceDataJob){
      Job s3Job = this.getWorkflow().createBashJob("S3_upload");
      if (skipupload) {
    	  s3Job.getCommand().addArgument("# Skip upload was turned on in your ini file \n");
    	  s3Job.getCommand().addArgument("exit 0 \n");
      }
	  s3Job.getCommand().addArgument("cd " + SHARED_WORKSPACE + "/downloads \n");
      s3Job.getCommand().addArgument("date +%s > ../upload_timing.txt \n");
      assert(this.downloadUrls.size() == this.analysisIds.size());
      for (int index = 0; index < this.downloadUrls.size(); index++) {
    	  // Execute the collab tool, mounting the downloads folder into /collab/upload
    	  s3Job.getCommand().addArgument("set +o errexit \n");
    	  s3Job.getCommand().addArgument("set +o pipefail \n");
    	  s3Job.getCommand().addArgument("fail=0 \n");
    	  s3Job.getCommand().addArgument("docker run "
    			  + "-v `pwd`:/collab/upload "
    			  + "-v " + this.collabCertPath + ":/collab/storage/conf/client.jks "
    			  + "-e ACCESSTOKEN=`cat " + this.collabToken + " | tr -d '\\n'` "
    			  + "--net=\"host\" "
    			  + "-e CLIENT_STRICT_SSL=\"True\" "
    			  + "-e CLIENT_UPLOAD_SERVICEHOSTNAME=" + this.collabHost + " " + this.collabDockerName
    			  + " bash -c \"/collab/upload.sh /collab/upload/" + this.analysisIds.get(index) + "\" \n"
    			  );
    	  s3Job.getCommand().addArgument("fail=$? \n");
    	  s3Job.getCommand().addArgument("echo \"Received a $fail exit code from the upload container.\" \n");
    	  s3Job.getCommand().addArgument("for x in logs/*; do sudo mv $x \"logs/" + this.analysisIds.get(index) + "_$(date +%s | tr -d '\\n')_$(basename $x | tr -d '\\n')\"; done \n");
    	  s3Job.getCommand().addArgument("docker run "
    			  + "-v `pwd`:/collab/upload "
    			  + "-v " + this.collabS3Cfg + ":/s3cfg "
    			  + "--net=\"host\" " + this.collabDockerName
    			  + " bash -c \"s3cmd put /collab/upload/logs/* " + this.collabLogBucket + " -c /s3cfg\" \n"
    			  );
    	  s3Job.getCommand().addArgument("fail2=$? \n");
    	  s3Job.getCommand().addArgument("echo \"Received a $fail2 exit code from the logging container.\" \n");
    	  s3Job.getCommand().addArgument("if [[ $fail2 -ne 0 ]]; then \n");
    	  s3Job.getCommand().addArgument("	fail=$fail2 \n");
    	  s3Job.getCommand().addArgument("fi \n");
    	  s3Job.getCommand().addArgument("sudo mv logs .. \n");
      }
      s3Job.getCommand().addArgument("du -c . | grep total | awk '{ print $1 }' > ../upload.size \n");
      s3Job.getCommand().addArgument("cd .. \n");
      s3Job.getCommand().addArgument("date +%s >> upload_timing.txt \n");
      s3Job.getCommand().addArgument("date +%s >> workflow_timing.txt \n");
      s3Job.getCommand().addArgument("# $fail will return a non-zero value if either docker container call fails \n");
      s3Job.getCommand().addArgument("exit $fail \n");
      s3Job.addParent(getReferenceDataJob);
      return s3Job;
    }
  
    private Job createVerifyJob(Job getReferenceDataJob) {
    	Job verifyJob = this.getWorkflow().createBashJob("Download_Verify");
    	verifyJob.getCommand().addArgument("cd " + SHARED_WORKSPACE + "/downloads \n");
    	int index = 0;
    	for (String url : this.downloadMetadataUrls) {
    		verifyJob.getCommand().addArgument("python " + this.getWorkflowBaseDir() + "/scripts/download_check.py " + url + " " + this.jsonXmlHash
				    + " \n");
    		verifyJob.getCommand().addArgument("mv "
  				  + this.analysisIds.get(index) + ".xml "
  				  + this.analysisIds.get(index) + " \n");
    		index += 1;
    	}
    	verifyJob.addParent(getReferenceDataJob);
    	return verifyJob;
    }

}
