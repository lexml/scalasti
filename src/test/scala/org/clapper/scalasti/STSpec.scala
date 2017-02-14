package org.clapper.scalasti

import java.io.{FileWriter, StringWriter}
import java.util.Locale

import grizzled.file.util.{joinPath, withTemporaryDirectory}
import grizzled.util._
import grizzled.util.CanReleaseResource.Implicits.CanReleaseAutoCloseable
import grizzled.util.CanReleaseResource.Implicits.CanReleaseSource
import org.stringtemplate.v4.NoIndentWriter

import scala.io.Source

/**
  * Tests the grizzled.io functions.
  */
class STSpec extends BaseSpec {
  // Type tags aren't available on nested classes (i.e., classes inside a
  // function).
  case class Value(s: String)

  object ValueRenderer extends AttributeRenderer[Value] {
    override def toString(v: Value, formatString: String, locale: Locale): String = {
      v.s
    }
  }

  val TemplateGroupString =
    """
      |delimiters "$", "$"
      |template(x) ::= <<This is a $x$ template>>
    """.stripMargin

  "render()" should "render a simple template with simple substitutions" in {
    val template = """This is a <test> template: <many; separator=", ">"""

    val data = List(
      (Map("test" -> "test",
           "many" -> List("a", "b", "c")),
       """This is a test template: a, b, c"""),

      (Map("test" -> "foo",
           "many" -> List("moe", "larry", "curley")),
       """This is a foo template: moe, larry, curley""")
    )

    for((attributes, expected) <- data)
      assertResult(expected, "render template on: " + attributes) {
        val st = ST(template).setAttributes(attributes)
        st.render()
      }
  }

  it should "render a template with '$' delimiters" in {
    val template = """This is a $test$ template: $many; separator=", "$"""

    val data = List(
      (Map("test" -> true,
           "many" -> List("a", "b", "c")),
       """This is a true template: a, b, c""")
    )

    for((attributes, expected) <- data)
      assertResult(expected, "render template on: " + attributes) {
        val st = ST(template, '$', '$').setAttributes(attributes)
        st.render()
      }
  }

  it should "allow a custom ValueRenderer" in {
    object ValueRenderer extends AttributeRenderer[Value] {
      def toString(v: Value, formatString: String, locale: Locale): String = {
        "<" + v.s + ">"
      }
    }

    val g = STGroupString(TemplateGroupString)
    val g2 = g.registerRenderer(ValueRenderer)
    val tST = g2.instanceOf("/template")
    tST shouldBe 'success
    val st = tST.get
    val st2 = st.add("x", Value("foo"), raw = true)
  }

  it should "handle automatic aggregates" in {
    val template = """|$if (page.title)$$page.title$$else$No title$endif$
                      |$page.categories; separator=", "$""".stripMargin

    val data = List(
      ("No title\nfoo, bar",
       "page.{categories}",
       List(List("foo", "bar"))),

      ("Foo\nmoe, larry, curley",
       "page.{title, categories}",
       List("Foo", List("moe", "larry", "curley")))
    )

    for ((expected, aggrSpec, args) <- data) {
      val st = ST(template, '$', '$').addAggregate(aggrSpec, args: _*)
      st.render() shouldBe expected
    }
  }

  it should "handle mapped aggregates" in {
    val template = "<thing.outer.inner> <foo.bar> <foo.baz> " +
                   "<thing.outer.x> <thing.okay>"

    val thingMap = Map("okay"  -> "OKAY",
                       "outer" -> Map("inner" -> "an inner string",
                                      "x"     -> "something else"))
    val fooMap = Map("bar" -> "BARSKI",
                     "baz" -> 42)

    val expected = "an inner string BARSKI 42 something else OKAY"
    val st = ST(template).addMappedAggregate("thing", thingMap)
                         .addMappedAggregate("foo", fooMap)
    st.render() shouldBe expected
  }

  it should "handle multivalue attributes" in {
    case class User(firstName: String, lastName: String) {
      override def toString: String = firstName + " " + lastName
    }

    val u1 = User("Elvis", "Presley")
    val u2 = User("Frank", "Sinatra")
    val users = u1 :: u2 :: Nil

    val t1 = "Hi, <user.firstName> <user.lastName>."
    ST(t1).add("user", u1).render() shouldBe "Hi, Elvis Presley."

    val t2 = "<users; separator=\", \">"
    ST(t2).add("users", users).render() shouldBe "Elvis Presley, Frank Sinatra"
  }

  it should "handle numeric typed attribute retrieval" in {
    val st = ST("Point = (<x>, <y>)")
      .add("x", 10)
      .add("y", 20)

    st.attribute[Int]("x") shouldBe Some(10)
    st.attribute[Int]("y") shouldBe Some(20)
    st.attribute[Double]("x") shouldBe None
    st.attribute[Double]("y") shouldBe None
    st.render() shouldBe "Point = (10, 20)"
  }

  it should "handle string typed attribute retrieval" in {
    val st = ST("<s>").add("s", "foo")
    st.render() shouldBe "foo"
    st.attribute[String]("s") shouldBe Some("foo")
    st.attribute[Int]("s") shouldBe None
  }

  it should "handle optional String typed attribute retrieval" in {
    val st = ST("<s>").add("s", Some("foo"))
    st.render() shouldBe "foo"
    st.attribute[String]("s") shouldBe Some("foo")
  }

  it should "handle None-typed attribute retrieval" in {
    val st = ST("<s>").add("s", None)
    st.render() shouldBe ""
    st.attribute[AnyRef]("s") shouldBe None
    st.attribute[String]("s") shouldBe None
  }

  it should "handle custom typed attribute retrieval" in {
    val groupString =
      """
        |delimiters "$", "$"
        |template(x) ::= <<This is a $x$ template>>
      """.stripMargin

    val group = STGroupString(groupString)
    val group2 = group.registerRenderer(ValueRenderer)
    val tST = group2.instanceOf("template")
    tST shouldBe 'success

    val st = tST.get
    val v = Value("foo")
    val st2 = st.add("x", v, raw=true)
    val vOpt = st2.attribute[Value]("x")
    vOpt shouldBe 'defined
    vOpt.get shouldEqual v
  }

  it should "render properly with a custom renderer" in {
    val group = STGroupString(TemplateGroupString)
    val group2 = group.registerRenderer(ValueRenderer)
    val tST = group2.instanceOf("template")
    tST shouldBe 'success

    val st = tST.get
    val v = Value("foo")
    val st2 = st.add("x", v, raw=true)
    st2.render() shouldBe "This is a foo template"
  }

  it should "properly substitute from a Some and a None" in {
    val st = ST("x=<x>, y=<y>")

    def add(label: String, o: Option[Int]) = st.add(label, o)

    val st2 = add("x", Some(10)).add("y", None)
    st2.render() shouldBe "x=10, y="
  }

  "add()" should "be immutable" in {
    val st = ST("abc=<abc>")
    val st2 = st.add("abc", "def")

    st2.render() shouldBe "abc=def"
    st2.native should not be theSameInstanceAs (st.native)
    st2 should not be theSameInstanceAs (st)
    st2.attribute[String]("abc") shouldBe Some("def")
    st.attribute[String]("abc") shouldBe None
    st.attributes should not be st2.attributes
  }

  it should "replace existing attributes" in {
    val attrs = Map("x" -> 1, "y" -> 2, "z" -> 3)
    val st = ST("x=<x>, y=<y>, z=<z>").addAttributes(attrs)
    val st2 = st.add("x", 10)

    st2.render() shouldBe "x=10, y=2, z=3"
    st.render() shouldBe "x=1, y=2, z=3"
  }

  "set()" should "be immutable" in {
    val st = ST("abc=<abc>")
    val st2 = st.set("abc", "def")
    val st3 = st2.set("abc", "123")

    st2.render() shouldBe "abc=def"
    st3.render() shouldBe "abc=123"
    intercept[Exception] {
      st.render()
    }
    st2.native should not be theSameInstanceAs (st.native)
    st3.native should not be theSameInstanceAs (st2.native)
    st2 should not be theSameInstanceAs (st)
    st3 should not be theSameInstanceAs (st2)
    st2.attribute[String]("abc") shouldBe Some("def")
    st3.attribute[String]("abc") shouldBe Some("123")
    st.attribute[String]("abc") shouldBe None
  }

  it should "replace existing attributes" in {
    val attrs = Map("x" -> 1, "y" -> 2, "z" -> 3)
    val st = ST("x=<x>, y=<y>, z=<z>").addAttributes(attrs)
    val st2 = st.set("x", 10)

    st2.render() shouldBe "x=10, y=2, z=3"
    st.render() shouldBe "x=1, y=2, z=3"
  }

  "addAttributes()" should "be immutable" in {
    val st = ST("x=<x>, y=<y>, z=<z>")
    val attrs = Map("x" -> 1, "y" -> 2, "z" -> 3)
    val st2 = st.addAttributes(attrs)

    st2.render() shouldBe "x=1, y=2, z=3"
    st2.native should not be theSameInstanceAs (st.native)
    st2 should not be theSameInstanceAs (st)
    st.attributes should not be st2.attributes

    for ((name, value) <- attrs)
      st2.attribute[Int](name) shouldBe Some(value)

    for (name <- attrs.keySet)
      st.attribute[Int](name) shouldBe None
  }

  it should "replace existing attributes" in {
    val st = ST("x=<x>, y=<y>, z=<z>")
    val attrs = Map("x" -> 1, "y" -> 2, "z" -> 3)
    val st2 = st.add("x", 10).add("y", 20).add("z", 30)
    val st3 = st2.addAttributes(attrs)

    st2.render() shouldBe "x=10, y=20, z=30"
    st3.render() shouldBe "x=1, y=2, z=3"
  }

  "setAttributes()" should "be immutable" in {
    val attrs = Map("x" -> 1, "y" -> 2, "z" -> 3)
    val st = ST("x=<x>, y=<y>, z=<z>").addAttributes(attrs)
    val st2 = st.addAttributes(attrs.mapValues(_ * 10))

    st.render() shouldBe "x=1, y=2, z=3"
    st2.render() shouldBe "x=10, y=20, z=30"
    st2.native should not be theSameInstanceAs (st.native)
    st2 should not be theSameInstanceAs (st)
    st.attributes should not be st2.attributes
  }

  "remove" should "be immutable" in {
    val attrs = Map("x" -> 1, "y" -> 2)
    val st = ST("x=<x>, y=<y>").addAttributes(attrs)
    val st2 = st.remove("y")

    st.render() shouldBe "x=1, y=2"
    intercept[Exception] {
      st2.render()
    }

    st2.native should not be theSameInstanceAs (st.native)
    st2 should not be theSameInstanceAs (st)
    st.attributes should not be st2.attributes
  }

  it should "return the same object if the key doesn't exist" in {
    val attrs = Map("x" -> 1, "y" -> 2)
    val st = ST("x=<x>, y=<y>").addAttributes(attrs)
    val st2 = st.remove("not-there")

    st.render() shouldBe "x=1, y=2"
    st2.render() shouldBe st.render()


    st2.native should be theSameInstanceAs st.native
    st2 should be theSameInstanceAs st
    st.attributes shouldBe st2.attributes
  }

  "write()" should "write the template to a file" in {

    withTemporaryDirectory("scalasti") { dir =>
      val filePath = joinPath(dir.getPath, "out.txt")
      withResource(new FileWriter(filePath)) { out =>
        val w = new NoIndentWriter(out)
        val group = STGroupString(TemplateGroupString)
        val tST = group.instanceOf("template")
        tST shouldBe 'success
        val template = tST.get.add("x", "foobar")
        val tWrite = template.write(w)
        tWrite shouldBe 'success
        tWrite.get shouldBe > (0)
      }

      withResource(Source.fromFile(filePath)) { src =>
        src.getLines.mkString("\n") shouldBe "This is a foobar template"
      }
    }
  }

  it should "write the template to a StringWriter" in {
    val buf = new StringWriter
    val w = new NoIndentWriter(buf)
    val group = STGroupString(TemplateGroupString)
    val tST = group.instanceOf("template")
    tST shouldBe 'success
    val template = tST.get.add("x", "foobar")
    val tWrite = template.write(w)
    tWrite shouldBe 'success
    tWrite.get shouldBe > (0)
    buf.toString shouldBe "This is a foobar template"
  }
}
