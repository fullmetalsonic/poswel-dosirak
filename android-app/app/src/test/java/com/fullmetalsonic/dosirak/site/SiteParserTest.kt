package com.fullmetalsonic.dosirak.site

import org.junit.Assert.*
import org.junit.Test

class SiteParserTest {
    @Test fun escapedMenuStringAndEmbeddedCommasRemainOneArgument() {
        val args = ScriptLiterals.arguments("goPay('741','한식','소스, 샐러드\\\' + \\uD55C\\x26','2026-10-09','5000','L',$(this));", "goPay")!!
        assertEquals(7, args.size); assertEquals("소스, 샐러드' + 한&", args[2])
    }
    @Test fun executableExpressionIsRejected() {
        assertNull(ScriptLiterals.arguments("goPay('741',attack(),'menu','2026-10-09','5000','L',$(this))", "goPay"))
        assertNull(ScriptLiterals.arguments("goPay('741','k','m','2026-10-09','5000','L',$(this));attack()", "goPay"))
    }
    @Test fun successfulControlsPreserveRepeatedFieldsAndCheckedState() {
        val doc = SiteParser.document("""<form><input name='a' value='1'><input name='a' value='2'><input name='ignored' disabled><input name='no' type='checkbox'><input name='yes' type='checkbox' checked><textarea name='text'>a&amp;+한글</textarea><select name='choice'><option value='a'>A</option><option value='b' selected>B</option></select></form>""")
        val fields = SiteParser.fields(doc.selectFirst("form")!!)
        assertEquals(listOf("1", "2"), fields["a"]); assertEquals(listOf("on"), fields["yes"])
        assertEquals(listOf("a&+한글"), fields["text"]); assertEquals(listOf("b"), fields["choice"])
        assertFalse(fields.containsKey("no")); assertFalse(fields.containsKey("ignored"))
    }
    @Test fun malformedMoneyCannotBeSilentlyParsed() {
        assertNull(SiteParser.amount("가격 없음")); assertNull(SiteParser.amount("50,00")); assertNull(SiteParser.amount("-5000"))
        assertEquals(5000L, SiteParser.amount("5,000 원"))
    }
}
