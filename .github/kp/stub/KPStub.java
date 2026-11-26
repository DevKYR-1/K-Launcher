/**
 * Stand-in for Minecraft in the K-Projects game test. It is NOT Minecraft: it only proves that the launcher can start
 * a Java process of the right version, that the process keeps running and that the launcher ends cleanly when it exits.
 * The graphics part (KPGl) opens a GLFW window with an OpenGL context the way the game does, and the result is
 * reported separately, because a graphics problem of the test machine (an emulator) says little about a phone.
 */
public class KPStub {
    public static void main(String[] args) throws Exception {
        System.out.println("KPSTUB-START java=" + System.getProperty("java.version")
                + " vm=" + System.getProperty("java.vm.name") + " arch=" + System.getProperty("os.arch")
                + " args=" + args.length);
        for (String arg : args) {
            // only the names of the options: their values hold paths, names and the (Local: dummy) token
            if (arg.startsWith("--")) System.out.println("KPSTUB-ARG " + arg);
        }

        Thread gl = new Thread(() -> {
            try {
                KPGl.run();
            } catch (Throwable t) {
                System.out.println("KPSTUB-GL-FAILED " + t);
                t.printStackTrace(System.out);
            }
        }, "kp-gl");
        gl.setDaemon(true);
        gl.start();
        gl.join(40000);
        System.out.println("KPSTUB-GL-THREAD " + (gl.isAlive() ? "STILL-RUNNING-AFTER-40s" : "finished"));

        for (int seconds = 10; seconds <= 60; seconds += 10) {
            Thread.sleep(10000);
            System.out.println("KPSTUB-ALIVE " + seconds + "s");
        }
        System.out.println("KPSTUB-DONE");
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }
}
