# Regular operations for a fleet running in production

Periodic checklist (daily+)

1) First pass, e.g. start of day
On slack, check the reporting bot using the commands `status` to see how many workers are running, how many workflows succeeded, and any failures. If there are running instances, `gather` shows each worker's current step in the workflow.

Check Uchiwa/Sensu for red/amber status showing issues and alarms, e.g. long-gtdownload-check. Make note of any instances of concern.


2) Further ways to inspect if there are issues

AWS web console will show which instances are up and check graphs for unusual CPU usage, coarse view network traffic (see Grafana for aggregated traffic by time).

Grafana shows aggregated traffic for workers downloading from a particular GNOS repo. Green lines indicate download activity (specifically received traffic). Amber peaks correspond to upload.

Check GitHub repo https://github.com/ICGC-TCGA-PanCancer/s3-transfer-operations for state of jobs.

3) Review failed jobs. See https://github.com/ICGC-TCGA-PanCancer/pancancer_launcher/blob/develop/README.md#dealing-with-failure for how to re-run failed workers

4) Review jobs that are incomplete *and* have issues shown in Uchiwa, such as long downloads. They may need manual intervention. If you suspect a slow download (may be observed in Grafana and Sensu checks), ssh in to the worker and check the gtdownload logs in `/datastore/oozie.../shared-workspace/downloads` . See how long the worker has been at a slow download rate.
   Check http://pancancer.info/transfers.html for possible known transfer rate issues.
   Sometimes it can help to restart the download process by stopping the docker container; seqware will retry. Do `docker ps -a` to find the name of the upload\_download container, then do `docker stop <name-of-container>`. A new container should restart within seconds. The already downloaded parts of the file will need to be validated (this part is CPU intensive, so it may set of sensu checks) and can take a while depending on the amount there is to validate. Once that part is done, initializing the download process can take up to 45 minutes before data starts moving.
   If there continue to be problems with slow download, contact Michael Ainsworth at mainsworth@annaisystems.com and CC Help@AnnaiSystems.com with the analysis IDs (same as GNOS IDs) associated with those jobs.


Uploads can be verified (e.g. during testing or early production, this is not expected for full production unless only spot checking) by the following combined:
- the json file corresponding to the job arrives in the Git repo and directory's `completed/` subdirectory
- timing data will have been added in the repo's `timing-information/` folder
- use the above jsons to look up arrival of individual files in S3: search the `oicr.icgc` bucket's `data/` folder for the object ids shown in the json file. The files have been known to take up to 1.5 hours to finalize and appear in `data/`. If not present there, it should at least be found in the `uploads/` folder of the same bucket. If not present anywhere in S3 but GitHub shows it is completed with timing data, something is wrong, contact the stewards of the S3 bucket and upload tool (Andy and Bob).
- one could also spot check the uploads by downloading one of the smaller files and checking the contents

inf) End of day: Detach from the pancancer_launcher container (Ctrl-P, Ctrl-Q).

### Compare XML MD5 Hashsums

In each JSON job description file is a pregenerated MD5 hashsum for the GNOS Analysis XML file, this XML file is downloaded from the gt-repos using its REST service and an MD5 hashsum calculated on the returned data. The two MD5 hashsums should match, or the workflow will fail since the files are different. VerifyXMLHash.py compares the MD5 hashsum in the JSON files in the queued-jobs folder of the corresponding s3-transfer-operations/ queue with the MD5 hashsum of the response from the corresponding gt-repo using the analysis id specified in the JSON file.

If there is a mismatch, then the a sequence of git commands can be output as a script using the `-os` flag to move the inconsistent JSON file to the failed-jobs or backlog-jobs queue for editting.

**Typical workflow**:

1. Run VerifyXMLHash.py to find mismatching XML MD5 hashsums.

2. Use the `-os` flag to output git instructions to transfer the failed-jobs queue for correction.

**Example Usage**

Compare MD5 hashsums in all JSON files in queued-jobs:
```
python3 VerifyXMLHash.py
```
Move all failed jobs to backlog-jobs and output instructions to a script:
```
python3 VerifyXMLHash.py --dest 'backlog-jobs' -os > git_instrs.sh
```
