package typesafe

import java.nio.file.{Files, Path}
import java.util.Base64
import scala.collection.immutable.VectorMap

/** The image formats the API reads. */
enum ImageFormat(val wire: String):
  case Png extends ImageFormat("png")
  case Jpeg extends ImageFormat("jpeg")

/** An image, base64-encoded, for a call's `assets`. Images work on Jev 1.13 and later and are
  * experimental: TypeSafe calls them a danger-zone feature, not production-ready, and a call that
  * carries one is sent with `TypeSafe-Danger-Zone-Images: true`.
  *
  * Each image goes once into the call's assets, under a reference string of your choosing, and the
  * model sees it wherever that reference is written: in the state, an instruction or a criterion.
  * Every reference must be used somewhere.
  *
  * {{{
  * val door = Box("the front door in photo").named("door")
  * val res = client.systemOne(
  *   Json.obj("photo" -> Json.Str("photo")),
  *   Questions.of(door),
  *   CallOptions(model = Some("jev-1.14.0"), assets = VectorMap("photo" -> Image.read(Path.of("office.jpg"))))
  * )
  * res(door).box
  * }}}
  */
final case class Image private (format: ImageFormat, dataBase64: String):
  def toJson: Json =
    Json.Obj(VectorMap("type" -> Json.Str("image"), "data_base64" -> Json.Str(dataBase64), "format" -> Json.Str(format.wire)))

  override def toString: String = s"Image($format, ${dataBase64.length} base64 characters)"

object Image:
  /** An image from its bytes, its format read from them. Throws [[InvalidRequestException]] for
    * anything but a PNG or a JPEG. Size limits (256 to 1568 pixels a side) are left to the API.
    */
  def fromBytes(bytes: Array[Byte]): Image =
    val format = sniff(bytes).getOrElse(throw InvalidRequestException("An image must be a PNG or a JPEG."))
    new Image(format, Base64.getEncoder.encodeToString(bytes))

  /** An image read from a file; see [[fromBytes]]. */
  def read(path: Path): Image = fromBytes(Files.readAllBytes(path))

  /** An image already base64-encoded, e.g. taken from a data URL. */
  def fromBase64(dataBase64: String, format: ImageFormat): Image = new Image(format, dataBase64)

  private val PngMagic = Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

  private def sniff(bytes: Array[Byte]): Option[ImageFormat] =
    if bytes.startsWith(PngMagic) then Some(ImageFormat.Png)
    else if bytes.length >= 3 && bytes(0) == 0xff.toByte && bytes(1) == 0xd8.toByte && bytes(2) == 0xff.toByte then
      Some(ImageFormat.Jpeg)
    else None
