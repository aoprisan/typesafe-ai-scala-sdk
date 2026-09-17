package examples

import typesafe.*

/** sbt "Test/runMain examples.triage" (needs TYPESAFE_API_KEY). Confidence-gated routing. */
@main def triage(): Unit =
  val client = TypeSafeClient()

  val department = Choice(
    "Which team should handle this",
    "billing" -> "Payment or subscription issues",
    "technical" -> "Bugs or integration problems",
    "sales" -> "Pricing or account questions"
  ).named("department")
  val frustration = Score(
    "How frustrated the customer appears",
    "Calm, just stating facts",
    "Frustrated but civil",
    "Very angry, strong language"
  ).named("frustration")
  val urgent = Noul("The message conveys urgency or time-sensitivity").named("is_urgent")

  val ticket = "Hi, I've been trying to connect my Stripe account for 3 days and it keeps failing. " +
    "I'm losing sales. Please help ASAP."

  try
    val res = client.systemOne(ticket, Questions.of(department, frustration, urgent))
    val dept = res(department)
    val route = if dept.confidence >= 0.5 then dept.choice else "human-review"
    println(f"route → $route (confidence ${dept.confidence}%.2f)")
    println(f"frustration ${res(frustration).score}%.2f")
    println(s"urgent? ${res(urgent).isYes(0.8)}")
    println(s"request id ${res.requestId}, usage ${res.usage}")
  finally client.close()
