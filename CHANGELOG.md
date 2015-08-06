# Initial Version

# 0.5
- write small files to /datastore containing gnos information for SENSU aggregation
- added parameterization of the docker container used for GNOS download, and made code compatible with latest container version
- split md5 sum checking into a seperate entity than download checking- ie. check md5 sum first, before downloading for efficiency (don't download bad data...)
- added some diagnostic info when an md5 sum fails, so the operator atleast knows what it actually was from gnos
