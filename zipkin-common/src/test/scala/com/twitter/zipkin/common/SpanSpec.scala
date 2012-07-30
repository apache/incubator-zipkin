/*
 * Copyright 2012 Twitter Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.twitter.zipkin.common

import org.specs.Specification
import com.twitter.zipkin.Constants

class SpanSpec extends Specification {

  val annotationValue = "NONSENSE"
  val expectedAnnotation = Annotation(1, annotationValue, Some(Endpoint(1, 2, "service")))
  val expectedSpan = Span(12345, "methodcall", 666, None,
    List(expectedAnnotation), Nil)

  val annotation1 = Annotation(1, "value1", Some(Endpoint(1, 2, "service")))
  val annotation2 = Annotation(2, "value2", Some(Endpoint(3, 4, "Service"))) // upper case service name
  val annotation3 = Annotation(3, "value3", Some(Endpoint(5, 6, "service")))

  val spanWith3Annotations = Span(12345, "methodcall", 666, None,
    List(annotation1, annotation2, annotation3), Nil)


  "Span" should {

    "serviceNames is lowercase" in {
      val names = spanWith3Annotations.serviceNames
      names.size mustBe 1
      names.toSeq(0) mustBe "service"
    }

    "serviceNames" in {
      val map = expectedSpan.getAnnotationsAsMap
      val actualAnnotation = map.get(annotationValue).get
      expectedAnnotation mustEqual actualAnnotation
    }

    "merge two span parts" in {
      val ann1 = Annotation(1, "value1", Some(Endpoint(1, 2, "service")))
      val ann2 = Annotation(2, "value2", Some(Endpoint(3, 4, "service")))

      val span1 = Span(12345, "", 666, None, List(ann1), Nil, true)
      val span2 = Span(12345, "methodcall", 666, None, List(ann2), Nil, false)
      val expectedSpan = Span(12345, "methodcall", 666, None, List(ann1, ann2), Nil, true)
      val actualSpan = span1.mergeSpan(span2)
      actualSpan mustEqual expectedSpan
    }

    "merge span with Unknown span name with known span name" in {
      val span1 = Span(1, "Unknown", 2, None, List(), Seq())
      val span2 = Span(1, "get", 2, None, List(), Seq())

      span1.mergeSpan(span2).name mustEqual "get"
      span2.mergeSpan(span1).name mustEqual "get"
    }

    "return the first annotation" in {
      annotation1 mustEqual spanWith3Annotations.firstAnnotation.get
    }

    "return the last annotation" in {
      annotation3 mustEqual spanWith3Annotations.lastAnnotation.get
    }

    "know this is not a client side span" in {
      val spanSr = Span(1, "n", 2, None, List(Annotation(1, Constants.ServerRecv, None)), Nil)
      spanSr.isClientSide mustEqual false
    }

    "get duration" in {
      spanWith3Annotations.duration mustEqual Some(2)
    }

    "don't get duration duration when there are no annotations" in {
      val span = Span(1, "n", 2, None, List(), Nil)
      span.duration mustEqual None
    }

    "validate span" in {
      val cs = Annotation(1, Constants.ClientSend, None)
      val sr = Annotation(2, Constants.ServerRecv, None)
      val ss = Annotation(3, Constants.ServerSend, None)
      val cr = Annotation(4, Constants.ClientRecv, None)

      val cs2 = Annotation(5, Constants.ClientSend, None)

      val s1 = Span(1, "i", 123, None, List(cs, sr, ss, cr), Nil)
      s1.isValid mustEqual true

      val s3 = Span(1, "i", 123, None, List(cs, sr, ss, cr, cs2), Nil)
      s3.isValid mustEqual false
    }
  }
}
