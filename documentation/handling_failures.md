# Handling failures

Failed workers are those that have stopped in their workflow and will not continue again without intervention. Their existence can be known through the reporting bot on Slack.

These instructions are adapted from https://github.com/ICGC-TCGA-PanCancer/pancancer_launcher/blob/develop/README.md#dealing-with-failure modified for features and tips specific to this particular workflow.

Eventually, the workflow may be modified have failed jobs moved automatically to the Git failed-jobs/ folder. This makes sense if most of our failures can be resolved by requeuing the work later, which is what we expect most of the time for this particular workflow once we reach steady state. In the meantime, failed workers need to be addressed by a human.

This is one way to work through failed workers, but there are certainly other processes that get it done.

1. **Find failed workers** On Slack, go to the channel for this fleet *s3-upload-aws-va*, type the command `@<name_of_fleet_bot, e.g. cecilesfleet> status` a summary of the fleet will be shown, including a list of failed workers, if there are any. If there are none, you are done! Check again periodically.

2. **Login to workers** The launcher host machine and the workers are in the same security group, so if you are on the launcher, you can ssh to the workers using the private IP addresses listed in the status above. Login to the launcher host machine (but don't attach to the launcher container). This is where you might want to start a tmux or screen session, then you can ssh to your workers.

3. **Find out what is wrong** Usual places to check first: `~/arch3.log`, `/datastore/oozie.../generated-scripts/`, check which step was last run (ls -alrt) and inspect the logs. `docker ps -a` can be used as a quick indicator of a docker run step having been retried several times.
   * Failed at a download? Check the logs in `/datastore/oozie.../shared-workspace/downloads`. If downloads are consistently slow from particular sites, write to Michael Ainsworth mainsworth@annaisystems.com and CC Help@AnnaiSystems.com, Brian, and Christina. The information needed for Annai to investigate is: analysis ID (same as GNOS ID); find this in the json or ini files of a job; they may need the GNOS repo as well (e.g. EBI, ETRI, OSDC-ICGC, DKFZ, BSC).
   * Failed at upload? Depending on what the logs show, you may want to retry (#4). The additional logs for upload can be viewed from inside the icgc/cli container. We may have this logging available outside the container in future workflow versions. One way to view this: do `docker ps` to get the name of the container. Then `docker exec -it <name of container> bash`. Inside the container, see `~/collab/storage/logs/client.log`. You may see some exceptions that are not necessarily terrible: when attempting to download a part of a file, it may sometimes fail, but this tool is designed to retry. If you see that the part numbers that fail are changing over time, then it is likely ok. If in doubt, ask for advice from Andy or Bob.
   * Failed at a git management step? Usual checks: Is the file in the expected folder? Was it a git collision (less likely when batch-size is less than 4)?

4. **If it can be retried**
   * Edit `/datastore/oozie.../whitestar/state.json` by deleting 's28_install_dependencies_2.sh'. We do this in order to meet the requirements for any git_manage steps that follow during the retry.
   * Run this command
   ```
   docker run -h master -it -v /var/run/docker.sock:/var/run/docker.sock -v /workflows/Workflow_Bundle_StoreAndForward_1.0.8_SeqWare_1.1.0:/workflow -v /datastore:/datastore  -v /home/ubuntu/.gnos:/home/ubuntu/.gnos pancancer/seqware_whitestar_pancancer:1.1.1  bash -c "seqware workflow-run retry --working-dir /datastore/<oozie-folder-name-without-trailing-slash>"
   ```
      [Ref: https://github.com/ICGC-TCGA-PanCancer/DEWrapperWorkflow#retry-options
   Note that inside the containers we use, the directory is called "workflow" not "workflows" (no S)]
   * Upon successful completion of the rest of the workflow, terminate the instance (see #6 below)

5. **If it's a failure that can't be fixed at this point**
   * Retrieve the json file name: on the worker, at the beginning of `~/arch3.log` or at `/tmp/seqware....ini`; this information can also be found in the launcher container, in the database (`psql -U queue_user queue_status` then use ini, job_uuid from job and ip_address, job_uuid from provision), take your pick.
   * In a terminal where you have Git:
   ```
   cd <Git folder where the json for that job is presently (e.g. downloading-jobs/, uploading-jobs/)>
   git pull
   ```
   * Move the json to the Git failed-jobs/ folder using the recommended sequence of git commands
   ```
   git checkout master && git reset --hard origin/master && git fetch --all && git mv <JSONfileName.json> ../failed-jobs/. && git commit -m 'failed <step it failed at, e.g. 'download'> <possibly useful info, e.g. 'xml mismatch'> <JSONfileName>' && git push
   ```
      [Ref: https://github.com/ICGC-TCGA-PanCancer/s3-transfer-operations#important-notes]
   * Ensure that it truly git mv'd there (sometimes fails due to a worker pushing a change at the same time and you'll have to do a `git pull` before trying again)
   * Terminate the instance (see #6 below)
In the future, this step may be performed by the workflow, automatically moving any failed jobs to Git failed-jobs/ folder and then terminating the instance.

6. **Terminate instances** The workflow has now either finished successfully, or given up on for the time being (by moving the associated json to the Git failed-jobs/ folder). You can now terminate the instances the jobs were run on.
   * Option 1: Get the instance ID. If you're logged in to the worker, it's in the terminal prompt. Instance IDs are also shown in the status shown by the Slack bot (but it might not be immediately obvious which job is associated with which instance). Select this instance on the AWS web console (can use the search field near the top on the instances page). Right-click > Instance State > Terminate.
   * Option 2: Use the Reaper from inside the launcher container. This is nice if you know all the IP addresses of the workers because then you can terminate them in bulk, e.g. if you have some sort of automated process or a script. (If failures need to be investigated by a human, this can take time, and that time could be used to provision new workers while the next one is being investigated.) Collect all the IP addresses of interest in a file. We have one started in the launcher container `~/arch3/kill-list.json` and it has the format
    ```
    [
    "172.1.1.1",
    "172.2.2.2",
    "54.3.3.3"
    ]
    ```
Then call the Reaper `Reaper --kill-list kill-list.json` [Ref: https://github.com/ICGC-TCGA-PanCancer/pancancer_launcher]
The instances will be wiped out and new workers can be provisioned to take their place.
