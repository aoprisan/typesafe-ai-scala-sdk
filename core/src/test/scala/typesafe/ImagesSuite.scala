package typesafe

import scala.collection.immutable.VectorMap

/** Jev 1.14's danger zone: images, box questions and long context, each behind its header. */
class ImagesSuite extends munit.FunSuite:
  private val api = MockApi()
  import api.Reply

  override def afterAll(): Unit = api.stop()

  /** The JPEG magic bytes and nothing else: enough to be read as an image without being one. */
  private val jpeg = Array(0xff, 0xd8, 0xff, 0xe0).map(_.toByte)

  private val boxBody =
    """{"model":"jev-1.14.0","answers":{
      |"door":{"type":"box","box":{"top":0.25,"bottom":0.82,"left":0.44,"right":0.84},"confidence":0.92},
      |"open":{"type":"noul","noul":0.2}},
      |"usage":{"input_tokens":290,"output_tokens":21}}""".stripMargin

  private def client(longContext: Boolean = false) =
    TypeSafeClient(ClientConfig(apiKey = Some("sk-test"), baseUrl = Some(api.url), env = _ => None, longContext = longContext))

  private val door = Box("the front door in photo").named("door")
  private val open = Noul("the door in photo is open").named("open")

  test("an image goes under its reference, the call opts in, and the box decodes") {
    api.respond(_ => Reply(200, boxBody))
    val res = client().systemOne(
      Json.Obj(VectorMap("photo" -> Json.Str("photo"))),
      Questions.of(door, open),
      CallOptions(model = Some("jev-1.14.0"), assets = VectorMap("photo" -> Image.fromBytes(jpeg)))
    )
    val sent = api.requests.head
    assertEquals(sent.header("TypeSafe-Danger-Zone-Images"), Some("true"))
    assertEquals(sent.header("TypeSafe-Danger-Zone-Long-Context"), None)
    assertEquals(
      Json.parse(sent.body),
      Json.parse(
        """{"state":{"photo":"photo"},"model":"jev-1.14.0",
          |"questions":{"door":{"type":"box","instructions":"the front door in photo"},
          |"open":{"type":"noul","instructions":"the door in photo is open"}},
          |"assets":{"photo":{"type":"image","data_base64":"/9j/4A==","format":"jpeg"}}}""".stripMargin
      )
    )
    assertEquals(res(door), BoxAnswer(BoundingBox(0.25, 0.82, 0.44, 0.84), 0.92))
    assertEquals(res(door).box.toPixels(1000, 500), (440, 125, 840, 410))
    assertEquals(res.box("open"), None)
    assertEquals(res(open).noul, 0.2)
  }

  test("a box question opts in on its own, and a plain call does not") {
    api.respond(_ => Reply(200, boxBody))
    client().systemOne("photo", Questions.of(door))
    client().systemOne("text", Questions.of(open))
    assertEquals(api.requests.map(_.header("TypeSafe-Danger-Zone-Images")), List(Some("true"), None))
  }

  test("an image nothing refers to, or that is no PNG or JPEG, is refused before sending") {
    api.respond(_ => Reply(200, boxBody))
    intercept[InvalidRequestException](Image.fromBytes("GIF89a".getBytes))
    assertEquals(Image.fromBytes(Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)).format, ImageFormat.Png)
    val e = intercept[InvalidRequestException](
      client().systemOne("nothing named here", Questions.of(door), CallOptions(assets = VectorMap("picture" -> Image.fromBytes(jpeg))))
    )
    assert(e.getMessage.contains("\"picture\" is not referenced"), e.getMessage)
    assertEquals(api.requests, Nil)
  }

  test("long context is opt-in per client, and a call can say otherwise") {
    api.respond(_ => Reply(200, boxBody))
    client().systemOne("x", Questions.of(open))
    client().systemOne("x", Questions.of(open), CallOptions(longContext = Some(true)))
    client(longContext = true).systemOne("x", Questions.of(open))
    client(longContext = true).systemOne("x", Questions.of(open), CallOptions(longContext = Some(false)))
    assertEquals(
      api.requests.map(_.header("TypeSafe-Danger-Zone-Long-Context")),
      List(None, Some("true"), Some("true"), None)
    )
  }
