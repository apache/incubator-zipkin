#!/usr/bash/env ruby
# Copyright 2012 Twitter Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Script that runs a single scalding job

require 'optparse'
require 'ostruct'
require 'pp'
require 'date'

$HOST = "my.remote.host"

class OptparseJobArguments

  #
  # Return a structure describing the options.
  #
  def self.parse(args)
    # The options specified on the command line will be collected in *options*.
    # We set default values here.
    options = OpenStruct.new
    options.job = nil
    options.uses_settings = false
    options.uses_hadoop_config = false
    options.set_timezone = false
    options.dates = []
    options.output = ""
    options.preprocessor = false

    opts = OptionParser.new do |opts|
      opts.banner = "Usage: run_job.rb -j JOB -d DATE -o OUTPUT -p -t TIMEZONE -s SETTINGS -c CONFIG"

      opts.separator ""
      opts.separator "Specific options:"

      opts.on("-j", "--job JOBNAME",
              "The JOBNAME to run") do |job|
        options.job = job
      end

      opts.on("-d", "--date DATES", Array,
              "The DATES to run the job over. Expected format is %Y-%m-%dT%H:%M") do |dates|
        options.dates = dates.map{|date| DateTime.strptime(date, '%Y-%m-%dT%H:%M')}
      end

      opts.on("-o", "--output OUTPUT",
              "The OUTPUT file to write to") do |output|
        options.output = output
      end
      
      opts.on("-p", "--[no-]prep", "Run as preprocessor") do |v|
        options.preprocessor = true
      end

      opts.on("-t", "--tz [TIMEZONE]", "Specify timezone for job. Default is local time") do |timezone|
        options.set_timezone = true
        options.timezone = timezone || ''
      end

      opts.on("-s", "--settings [SETTINGS]", "Optional settings for the job") do |settings|
        options.uses_settings = true
        options.settings = settings || ''
      end

      opts.on("-c", "--config [CONFIG]", "Optional hadoop configurations for the job.") do |config|
        options.uses_hadoop_config = true
        options.hadoop_config = config || ''
      end


      opts.separator ""
      opts.separator "Common options:"      
      opts.on_tail("-h", "--help", "Show this message") do
        puts opts
        exit
      end
    end
    opts.parse!(args)
    options
  end
end

options = OptparseJobArguments.parse(ARGV)
start_date = options.dates.at(0)
end_date = options.dates.length > 1 ? options.dates.at(1) : options.dates.at(0)

def time_to_remote_file(time, prefix)
  return prefix + time.year.to_s() + "/" + append_zero(time.month) + "/" + append_zero(time.day) + "/" + append_zero(time.hour)
end

def append_zero(x)
  if 0 <= x and x <= 9
    0.to_s() + x.to_s()
  else
    x.to_s()
  end
end

# TODO: So hacky OMG what is this I don't even
def is_hadoop_local_machine?()
  return system("hadoop dfs -test -e .")
end

def remote_file_exists?(pathname, options)
  cmd = is_hadoop_local_machine?() ? "" : "ssh -C " + $HOST + " "
  cmd += "hadoop"
  cmd += options.uses_hadoop_config ? " --config " + options.hadoop_config : ""
  cmd += " dfs -test -e " + pathname
  result = system(cmd)
  puts "In run_job, remote_file_exists for " + pathname + ": " + result.to_s()
  return result
end

def date_to_cmd(date)
  return date.to_s()[0..18]
end

cmd_head = File.dirname(__FILE__) + "/scald.rb --hdfs com.twitter.zipkin.hadoop."
settings_string = options.uses_settings ? " " + options.settings : ""
cmd_date = date_to_cmd(start_date) + " " + date_to_cmd(end_date)
timezone_cmd = options.set_timezone ? " --tz " + options.timezone : ""
cmd_args = options.job + settings_string  + " --date " + cmd_date + timezone_cmd

if options.preprocessor
  if not remote_file_exists?(time_to_remote_file(end_date, options.job + "/") + "/_SUCCESS", options)
    cmd = cmd_head + "sources." + cmd_args
    system(cmd)
  end
else
  if not remote_file_exists?(options.output + "/_SUCCESS", options)
    cmd = cmd_head + cmd_args + " --output " + options.output
    system(cmd)
  end
end
