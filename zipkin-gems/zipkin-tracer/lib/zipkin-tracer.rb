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
require 'finagle-thrift'
require 'finagle-thrift/trace'

require 'zipkin-tracer/careless_scribe'

module ZipkinTracer extend self

  class RackHandler
    def initialize(app)
      @app = app
      @lock = Mutex.new
      ::Trace.tracer = ::Trace::ZipkinTracer.new(CarelessScribe.new(Scribe.new()), 10)
    end

    def call(env)
      # TODO HERE BE HACK. Chad made me do it!
      # this is due to our setup where statics are served through the ruby app, but we don't want to trace that.
      rp = env["REQUEST_PATH"]
      if !rp.blank? && (rp.starts_with?("/stylesheets") || rp.starts_with?("/javascripts") || rp.starts_with?("/images"))
        return @app.call(env)
      end

      id = ::Trace::TraceId.new(::Trace.generate_id, nil, ::Trace.generate_id, true)
      ::Trace.default_endpoint = ::Trace.default_endpoint.with_service_name("zipkinui").with_port(0) #TODO any way to get the port?
      ::Trace.sample_rate=(1)
      tracing_filter(id, env) { @app.call(env) }
    end

    private
    def tracing_filter(trace_id, env)
      @lock.synchronize do
        ::Trace.push(trace_id)
        ::Trace.set_rpc_name(env["REQUEST_METHOD"]) # get/post and all that jazz
        ::Trace.record(::Trace::BinaryAnnotation.new("http.uri", env["PATH_INFO"], "STRING", ::Trace.default_endpoint))
        ::Trace.record(::Trace::Annotation.new(::Trace::Annotation::SERVER_RECV, ::Trace.default_endpoint))
      end
      yield if block_given?
    ensure
      @lock.synchronize do
        ::Trace.record(::Trace::Annotation.new(::Trace::Annotation::SERVER_SEND, ::Trace.default_endpoint))
        ::Trace.pop
      end
    end
  end

end
