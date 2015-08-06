#!/bin/python

import sys

data = {}
data['download'] = "download_timing.txt"
data['upload'] = "upload_timing.txt"
data['workflow'] = "workflow_timing.txt"

tags = ['start', 'stop', 'delta']

start = {}
stop = {}
delta = {}

def main(uuid):
	for key, value in data.iteritems():
		with open(value) as f:
			timings = f.readlines()
		start[key] = float(timings[0])
		stop[key] = float(timings[1])
		delta[key] = float(timings[1]) - float(timings[0])

	with open("%s.timing" % uuid,"w") as f:
		iterable = list(data.keys())
		sorted(iterable)
		string = ""
		for i in iterable:
			for tag in tags:
				string += "%s-%s," % (i, tag)
		f.write(string + "\n")
		string = uuid + ","
		for key in iterable:
			string += "%s,%s,%s," % (start[key], stop[key], delta[key])
		f.write(string + "\n")

if __name__ == '__main__':
	main(sys.argv[1])

# USAGE: python timing.py [gnos-id]

