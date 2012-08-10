/*
* Copyright 2012 Twitter Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.twitter.zipkin.hadoop.email

import javax.mail._
import javax.mail.internet.{InternetAddress, MimeMessage}
import javax.mail.Message.RecipientType

/**
* An Email is a class that represents a single email (including text) and a single SMTP session, and which allows you
 * to send these emails.
*/

// TODO: Implement logging

class Email(adminFrom: String, testTo: String, bcc: String,
            testMode: Boolean,
            smtpServer: String, smtpPort: Int,
            smtpAuth: Boolean, smtpUser: String, smtpPassword: String) {

  // Email session properties
  private val sessionProps = new java.util.Properties()
  sessionProps.put("mail.transport.protocol", "smtp");
  sessionProps.put("mail.smtp.host", smtpServer)
  sessionProps.put("mail.smtp.port", smtpPort.asInstanceOf[AnyRef])
  sessionProps.put("mail.smtp.auth", smtpAuth.asInstanceOf[AnyRef])


  /**
   * Sends a message with the given subject and body to the specified to list.
   *
   * @param to non-null list of addresses
   * @param subject non-null subject line
   * @param body non-null body of the message
   */
  def send(to: String, subject: String, body: String): Boolean = {
    send(Map(RecipientType.TO -> to), subject, body)
  }

  def send(_toMap: Map[RecipientType, String], _subject: String, _body: String): Boolean = {
    // Allow obs-related alerts to be delivered
    val toMap =
      if (testMode) {
        Map(RecipientType.TO -> testTo)
      } else {
        _toMap
      }
    val subject = if (testMode) "[TEST] %s [TEST]".format(_subject) else _subject

    try {
      val auth = new SMTPAuthenticator()
      val session = Session.getDefaultInstance(sessionProps, auth)
      val transport = session.getTransport()
      transport.connect(smtpUser, smtpPassword)

      // Create a new message
      val msg = new MimeMessage(session);
      msg.setFrom(new InternetAddress(adminFrom));
      toMap foreach {
        case (k, v) =>
          msg.addHeader(k.toString, v)
      }
      msg.addHeader("bcc", bcc)
      toMap.get(RecipientType.TO).foreach {
        msg.addHeader("in-reply-to", _)
      }
      msg.setSubject(subject)
      msg.setContent(_body, if (_body.indexOf("<html>") >= 0) "text/html" else "text/plain")
      msg.setSentDate(new java.util.Date())
      transport.sendMessage(msg, msg.getAllRecipients())
      true
    } catch {
      case e =>
        false
    }
  }

  private class SMTPAuthenticator extends javax.mail.Authenticator {
    override def getPasswordAuthentication(): PasswordAuthentication = {
      new PasswordAuthentication(smtpUser, smtpPassword);
    }
  }

}


