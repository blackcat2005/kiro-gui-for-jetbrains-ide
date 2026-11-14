package com.kirogui.jetbrains.jobs

import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class EvalGuardTest {
    private fun safe(e: String) = EvalGuard.check(e, EvalGuard.SAFE)
    private fun ro(e: String) = EvalGuard.check(e, EvalGuard.READ_ONLY)

    @Test fun `safe mode lets ordinary expressions through`() {
        assertNull(safe("user.name"))
        assertNull(safe("list.size() > 3"))
        assertNull(safe("counter = counter + 1"))
        assertNull(safe("service.reload()"))
    }

    @Test fun `safe mode blocks processes, exit, files, network and reflection`() {
        listOf(
            "Runtime.getRuntime().exec(\"rm -rf /\")", "new ProcessBuilder(\"ls\").start()", "System.exit(1)",
            "java.nio.file.Files.delete(p)", "new File(\"x\").delete()", "new java.net.URL(u).openStream()",
            "Class.forName(\"x\")", "m.setAccessible(true)", "os.system('ls')", "subprocess.run(['ls'])",
            "__import__('os')", "open('f').read()", "require('child_process')", "process.env.X", "System.getenv(\"PATH\")",
        ).forEach { assertNotNull(safe(it), it) }
    }

    @Test fun `words inside string literals do not trip the guard`() {
        assertNull(safe("name.equals(\"System.exit\")"))
        assertNull(ro("message.contains(\"Runtime.getRuntime\")"))
    }

    @Test fun `read-only mode allows plain reads and getters`() {
        listOf("a.b.c", "items[2]", "user.getName()", "list.size() == 3", "map.containsKey(k)", "x.toString()", "a == b", "a != b && c <= d", "len(rows)", "x => y")
            .forEach { assertNull(ro(it), it) }
    }

    @Test fun `read-only mode refuses writes, fragments, new and unknown calls`() {
        listOf("count = 5", "i++", "--i", "total += 2", "x := 1", "a; b", "new Foo()", "service.reload()", "cache.clear()", "list.add(1)")
            .forEach { assertNotNull(ro(it), it) }
    }

    @Test fun `unrestricted allows everything`() {
        assertNull(EvalGuard.check("Runtime.getRuntime().exec(\"x\")", EvalGuard.UNRESTRICTED))
    }
}
