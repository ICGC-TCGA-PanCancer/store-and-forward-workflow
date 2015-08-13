package io.seqware.pancancer;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map.Entry;
import java.lang.Long;
import net.sourceforge.seqware.pipeline.workflowV2.AbstractWorkflowDataModel;
import net.sourceforge.seqware.pipeline.workflowV2.model.Job;

public class StoreAndForward extends AbstractWorkflowDataModel {
  
    // job utilities
    private JobUtilities utils = new JobUtilities();

    // Common Variables
    private static final String SHARED_WORKSPACE = "shared_workspace";
    private ArrayList<String> analysisIds = null;
    private ArrayList<String> downloadUrls = null;
    private ArrayList<String> downloadMetadataUrls = null;
    private String gnosServer = null;
    private String pemFile = null;
    private String formattedDate;
    // Booleans for sequence control
    private Boolean skipdownload = false;
    private Boolean skipupload = false;
    private Boolean cleanup = true;
    // JSON Configuration
    private String JSONrepo = null;
    private String JSONlocation = "/datastore/gitroot";
    private String JSONrepoName = "s3-transfer-operations";
    private String JSONfolderName = null;
    private String JSONfileName = null;
    private String JSONxmlHash = null;
    // Git Configuration
    private String GITemail = "nbyrne.oicr@gmail.com";
    private String GITname = "ICGC AUTOMATION";
    private String GITPemFile = null;
    // Collab Tool
    private String collabToken = null;
    private String collabCertPath = null;
    private String collabHost = null;
    // Collab Tool Logging
    private String collabLogKey = null;
    private String collabLogSecret = null;
    private String collabLogBucket = null;
    // Docker Config
    private String gnosDockerName = null;
    private String collabDockerName = null;
    // Gnos Timing
    private int gnosTimeoutMin = 20;
    private int gnosRetries = 3;
    
    @Override
    public void setupWorkflow() {
        try {
          
            // Idenfify Content
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
            this.collabLogKey = getProperty("collabLogKey");
            this.collabLogSecret = getProperty("collabLogSecret");
            
            // Docker Config
            this.gnosDockerName = getProperty("gnosDockerName");
            this.collabDockerName = getProperty("collabDockerName");
            
            // JSON Git Repo
            this.JSONrepo = getProperty("JSONrepo");
            this.JSONfolderName = getProperty("JSONfolderName");
            this.JSONfileName = getProperty("JSONfileName");
            this.JSONxmlHash = getProperty("JSONxmlHash");
            this.GITemail = getProperty("GITemail");
            this.GITname = getProperty("GITname");
            this.GITPemFile = getProperty("GITPemFile");

            // record the date
            DateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
            Calendar cal = Calendar.getInstance();
            this.formattedDate = dateFormat.format(cal.getTime());
            
            // GNOS timeouts
            if(hasPropertyAndNotNull("gnosTimeoutMin"))
            		this.gnosTimeoutMin = Integer.parseInt(getProperty("gnosTimeoutMin"));
            if(hasPropertyAndNotNull("gnosRetries"))
            		this.gnosRetries = Integer.parseInt(getProperty("gnosRetries"));
	    
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
        Job s3Upload = S3toolJob(move2upload);
	
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
    	String path = this.JSONlocation + "/" +  this.JSONrepoName + "/" + this.JSONfolderName;
    	String gitroot = this.JSONlocation + "/" +  this.JSONrepoName;
    	manageGit.getCommand().addArgument("git config --global user.name " + this.GITname + " \n");
    	manageGit.getCommand().addArgument("git config --global user.email " + this.GITemail + " \n");
    	manageGit.getCommand().addArgument("if [[ ! -d " + path + " ]]; then mkdir -p " + path + "; fi \n");
    	manageGit.getCommand().addArgument("cd " + path + " \n");
    	manageGit.getCommand().addArgument("# This is not idempotent: git pull \n");
    	manageGit.getCommand().addArgument("git checkout master \n");
    	manageGit.getCommand().addArgument("git reset --hard origin/master \n");
    	manageGit.getCommand().addArgument("git fetch --all \n");
    	manageGit.getCommand().addArgument("if [[ ! -d " + dst + " ]]; then mkdir " + dst + "; fi \n");
    	manageGit.getCommand().addArgument("if [[ -d " + src + " ]]; then git mv " + path + "/" + src + "/" + this.JSONfileName + " " + path + "/" + dst + "; fi \n");
    	manageGit.getCommand().addArgument("git stage . \n");
    	manageGit.getCommand().addArgument("git commit -m '" + dst + ": " + this.JSONfileName +"' \n");
    	manageGit.getCommand().addArgument("git push \n");
    	manageGit.addParent(lastJob);
    	return(manageGit);
    }
    
    private Job gitTiming(Job lastJob) {
    	Job consolidateTiming = this.getWorkflow().createBashJob("git_timing");
    	consolidateTiming.getCommand().addArgument("cd " + SHARED_WORKSPACE + " \n");
    	String path = this.JSONlocation + "/" +  this.JSONrepoName + "/timing-information";
    	String gitroot = this.JSONlocation + "/" +  this.JSONrepoName;
    	consolidateTiming.getCommand().addArgument("if [[ ! -d " + path + " ]]; then mkdir -p " + path + "; fi \n");
    	int index = 0;
    	for (String url : this.downloadUrls) {
    		consolidateTiming.getCommand().addArgument("sharedpath=`pwd` \n");
	    	consolidateTiming.getCommand().addArgument("python " + this.getWorkflowBaseDir() + "/scripts/timing.py " + this.analysisIds.get(index) +" \n");
	    	consolidateTiming.getCommand().addArgument("cd " + path + " \n");
	    	consolidateTiming.getCommand().addArgument("git checkout master \n");
	    	consolidateTiming.getCommand().addArgument("git reset --hard origin/master \n");
	    	consolidateTiming.getCommand().addArgument("git fetch --all \n");
	    	consolidateTiming.getCommand().addArgument("mv ${sharedpath}/" + this.analysisIds.get(index) + ".timing " + path + "/" + this.JSONfileName + ".timing \n");
	    	consolidateTiming.getCommand().addArgument("git stage . \n");
	    	consolidateTiming.getCommand().addArgument("git commit -m 'Timing for: " + this.analysisIds.get(index) + "' \n");
	    	consolidateTiming.getCommand().addArgument("git push \n");
	    	index += 1;
    	}
    	consolidateTiming.addParent(lastJob);
    	return(consolidateTiming);
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
		return(createSharedWorkSpaceJob);
    }

    private Job pullRepo(Job getReferenceDataJob) {
    	Job installerJob = this.getWorkflow().createBashJob("install_dependencies");
    	installerJob.getCommand().addArgument("if [[ ! -d ~/.ssh/ ]]; then  mkdir ~/.ssh; fi \n");
    	installerJob.getCommand().addArgument("cp " + this.GITPemFile + " ~/.ssh/id_rsa \n");
    	installerJob.getCommand().addArgument("chmod 600 ~/.ssh/id_rsa \n");
    	installerJob.getCommand().addArgument("echo 'StrictHostKeyChecking no' > ~/.ssh/config \n");
    	installerJob.getCommand().addArgument("if [[ -d " + this.JSONlocation + " ]]; then  exit 0; fi \n");
    	installerJob.getCommand().addArgument("mkdir -p " + this.JSONlocation + " \n");
    	installerJob.getCommand().addArgument("cd " + this.JSONlocation + " \n");
    	installerJob.getCommand().addArgument("git config --global user.name " + this.GITname + " \n");
    	installerJob.getCommand().addArgument("git config --global user.email " + this.GITemail + " \n");
    	installerJob.getCommand().addArgument("git clone " + this.JSONrepo + " \n");
    	installerJob.addParent(getReferenceDataJob);
    	return(installerJob);
    }
    
    private Job createGNOSJob(Job getReferenceDataJob) {
	  Job GNOSjob = this.getWorkflow().createBashJob("GNOS_download");
	  if (this.skipdownload == true) {
		  GNOSjob.getCommand().addArgument("# You have skip download enabled in your ini file.  \n");
		  GNOSjob.getCommand().addArgument("exit 0 \n");
	  }
	  GNOSjob.getCommand().addArgument("cd " + SHARED_WORKSPACE + "/downloads \n");
	  int index = 0;
	  GNOSjob.getCommand().addArgument("date +%s > ../download_timing.txt \n");
	  for (String url : this.downloadUrls) {
		  // Add GNOS tag for download
		  String server = this.gnosServer.replace("http://", "");
		  server = server.replace("https://", "");
		  server = server.replace("/", "");
		  server = server.replace("gtrepo-", "");
		  server = server.replace(".", "-");
		  GNOSjob.getCommand().addArgument("echo -n \"" + server + "\" > /datastore/gnos_id.txt \n");
		  GNOSjob.getCommand().addArgument("python " + this.getWorkflowBaseDir() + "/scripts/md5_check.py " + this.downloadMetadataUrls.get(index) + " " + this.JSONxmlHash + " \n");
		  GNOSjob.getCommand().addArgument("mv patched.xml " + this.analysisIds.get(index) + ".xml \n");
		  GNOSjob.getCommand().addArgument("echo '" + url + "' > individual_download_timing.txt \n");
		  GNOSjob.getCommand().addArgument("date +%s > individual_download_timing.txt \n");
		  GNOSjob.getCommand().addArgument("docker run "
					      // link in the input directory
					      + "-v `pwd`:/workflow_data "
					      // link in the pem key
					      + "-v "
					      + this.pemFile
					      + ":/gnos_icgc_keyfile.pem " + this.gnosDockerName
					      // here is the Bash command to be run
					      + " /bin/bash -c \"cd /workflow_data/ && perl -I /opt/gt-download-upload-wrapper/gt-download-upload-wrapper-2.0.11/lib "
					      + "/opt/vcf-uploader/vcf-uploader-2.0.5/gnos_download_file.pl "
					      + "--url " + url + " . "
					      + " --retries " + this.gnosRetries + " --timeout-min " + this.gnosTimeoutMin + " "
					      + " --file /gnos_icgc_keyfile.pem "
					      + " --pem /gnos_icgc_keyfile.pem\" \n");
		  GNOSjob.getCommand().addArgument("sudo chown -R seqware:seqware " + this.analysisIds.get(index) + " \n");
		  GNOSjob.getCommand().addArgument("date +%s >> individual_download_timing.txt \n");
		  index += 1;
	  }
	  GNOSjob.getCommand().addArgument("date +%s >> ../download_timing.txt \n");
	  GNOSjob.getCommand().addArgument("du -c . | grep total | awk '{ print $1 }' > ../download.size \n");
	  GNOSjob.getCommand().addArgument("cd - \n");
	  GNOSjob.addParent(getReferenceDataJob);
	  return(GNOSjob);
    }
    
    private Job S3toolJob( Job getReferenceDataJob) {
      Job S3job = this.getWorkflow().createBashJob("S3_upload");
      if (skipupload == true) {
    	  S3job.getCommand().addArgument("# Skip upload was turned on in your ini file \n");
    	  S3job.getCommand().addArgument("exit 0 \n");
      }
	  S3job.getCommand().addArgument("cd " + SHARED_WORKSPACE + "/downloads \n");
      S3job.getCommand().addArgument("date +%s > ../upload_timing.txt \n");
      int index = 0;
      for (String url : this.downloadUrls) {
    	  // Execute the collab tool, mounting the downloads folder into /collab/upload
    	  String folder = analysisIds.get(index);
    	  S3job.getCommand().addArgument("set +o errexit \n");
    	  S3job.getCommand().addArgument("set +o pipefail \n");
    	  S3job.getCommand().addArgument("fail=0 \n");
    	  S3job.getCommand().addArgument("docker run "
    			  + "-v `pwd`:/collab/upload "
    			  + "-v " + this.collabCertPath + ":/collab/storage/conf/client.jks "
    			  + "-e ACCESSTOKEN=" + this.collabToken + " "
    			  + "--net=\"host\" "
    			  + "-e CLIENT_STRICT_SSL=\"True\" "
    			  + "-e CLIENT_UPLOAD_SERVICEHOSTNAME=" + this.collabHost + " " + this.collabDockerName
    			  + " bash -c \"/collab/upload.sh /collab/upload/" + this.analysisIds.get(index) + "\" \n"
    			  );
    	  S3job.getCommand().addArgument("fail=$? \n");
    	  S3job.getCommand().addArgument("echo \"Received a $fail exit code from the upload container.\" \n");
    	  S3job.getCommand().addArgument("cd " + this.analysisIds.get(index) + " \n");
    	  S3job.getCommand().addArgument("for x in logs/*; do sudo mv $x \"logs/" + this.analysisIds.get(index) + "_$(date +%s | tr -d '\\n')_$(basename $x | tr -d '\\n')\"; done \n");
    	  S3job.getCommand().addArgument("docker run "
    			  + "-v `pwd`:/collab/upload "
    			  + "--net=\"host\" " + this.collabDockerName
    			  + " bash -c \"s3cmd put /collab/upload/logs/* " + this.collabLogBucket + " --secret_key=" + this.collabLogSecret + " --access_key=" + this.collabLogKey + "\" \n"
    			  );
    	  // S3job.getCommand().addArgument("sudo mv logs ../logs.uploaded \n");
    	  S3job.getCommand().addArgument("cd .. \n");
    	  index += 1;
      }
      S3job.getCommand().addArgument("du -c . | grep total | awk '{ print $1 }' > ../upload.size \n");
      S3job.getCommand().addArgument("cd .. \n");
      S3job.getCommand().addArgument("date +%s >> upload_timing.txt \n");
      S3job.getCommand().addArgument("date +%s >> workflow_timing.txt \n");
      S3job.getCommand().addArgument("exit $fail \n");
      S3job.addParent(getReferenceDataJob);
      return(S3job);
    }
  
    private Job createVerifyJob(Job getReferenceDataJob) {
    	Job verifyJob = this.getWorkflow().createBashJob("Download_Verify");
    	verifyJob.getCommand().addArgument("cd " + SHARED_WORKSPACE + "/downloads \n");
    	int index = 0;
    	for (String url : this.downloadMetadataUrls) {
    		verifyJob.getCommand().addArgument("python " + this.getWorkflowBaseDir() + "/scripts/download_check.py " + url + " " + this.JSONxmlHash + " \n");
    		verifyJob.getCommand().addArgument("mv "
  				  + this.analysisIds.get(index) + ".xml "
  				  + this.analysisIds.get(index) + " \n");
    		index += 1;
    	}
    	verifyJob.addParent(getReferenceDataJob);
    	return(verifyJob);
    }

}
