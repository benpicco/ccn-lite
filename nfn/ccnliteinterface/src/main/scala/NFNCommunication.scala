import scala.concurrent._

import ExecutionContext.Implicits.global

import java.io.File
import java.util.Date
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.xml.{Node, NodeSeq, Elem}

import javax.xml.bind.DatatypeConverter

import com.typesafe.scalalogging.slf4j.Logging

trait Packet {
  def name: Seq[String]
}

case class Interest(name: Seq[String]) extends Packet {
  override def toString = s"Interest('${name.mkString("/")}"
}
case class Content(name: Seq[String], data: Array[Byte]) extends Packet {
  override def toString = s"Content('${name.mkString("/")}' => ${new String(data)})"
}

object NFNCommunication extends Logging {
  def parseXml(xmlString: String):Packet = {

    def parseData(elem: Node): String = {
      val data = elem \ "data"

      val nameSize = (data \ "@size").text.toInt
      val encoding = (data \ "@dt").text
      val nameData = data.text.trim

      encoding match {
        case "string" =>
          assert(nameData.size == nameSize, s"Parsed name '$nameData' has not its actual size $nameSize")
          nameData
        case "binary.base64" =>
          new String(DatatypeConverter.parseBase64Binary(nameData))
        case _ => throw new Exception(s"parseData() does not support data of type: '$encoding'")
      }
    }

    def parseComponents(elem: Elem):Seq[String] = {
      val components = elem \ "name" \ "component"

      components.map { parseData }
    }

    def parseContent(elem: Elem): Array[Byte] = {
      val contents = elem \ "content"
      assert(contents.size == 1, "content should only contain one node with content")
      parseData(contents.head).getBytes
    }

//    val xmlStringTrimmed = xmlString.trim
//
//    val endOfFirstTag = xmlStringTrimmed.indexOf(">")
//    val firstTag = xmlStringTrimmed.substring(1, endOfFirstTag)
//
//    val endOfLastTag = xmlStringTrimmed.lastIndexOf(firstTag) + 1 + firstTag.size
//
//    val cleanedXmlString = xmlStringTrimmed.substring(0, endOfLastTag)
//
//    println("CLEANED XML: \n" + cleanedXmlString)jj

    // TODO use base64 encoding for raw data in xml
    val cleanedXmlString = xmlString.trim.replace("&", "&amp;")

    val xml: Elem = scala.xml.XML.loadString(cleanedXmlString)
    xml match {
      case interest @ <interest>{_*}</interest> => {
        val nameComponents = parseComponents(interest)
        Interest(nameComponents)
      }
      case content @ <contentobj>{_*}</contentobj> => {
        val nameComponents = parseComponents(content)
        val contentData = parseContent(content)
        Content(nameComponents, contentData)
      }
      case _ => throw new Exception("XML parser cannot parse:\n" + xml)
    }
  }

  def main(args: Array[String]) = {

    val socket = UDPClient()
    val ccnIf = new CCNLiteInterface()

    val interestName = "add 7 1/NFN"
    val interest: Array[Byte] = ccnIf.mkBinaryInterest(interestName)

    val f = socket.sendReceive(interest)
    val respInterest = Await.result(f, 1 minute)

    val xmlDataInterest = ccnIf.ccnbToXml(respInterest)

    parseXml(xmlDataInterest) match {
      case Content(name, data) =>
        val dataString = new String(data)
        val resultPrefix = "RST|"

        val resultContentString = dataString.startsWith(resultPrefix) match {
          case true => dataString.substring(resultPrefix.size)
          case false => throw new Exception(s"NFN could not compute result for: $interest")
        }
        logger.info(s"NFN: '$interestName' => '$resultContentString'")

      case Interest(name) => throw new Exception(s"Received a Interest from NFN. not implemented")
    }
  }
}

