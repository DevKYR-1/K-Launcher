import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/** The graphics half of the stub: window, OpenGL context, a few frames. Kept apart so that KPStub loads without LWJGL. */
class KPGl {
    static void run() {
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");
        System.out.println("KPSTUB-GL glfwInit ok");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
        long window = GLFW.glfwCreateWindow(640, 360, "KPStub", 0L, 0L);
        if (window == 0L) throw new IllegalStateException("glfwCreateWindow returned 0");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("KPSTUB-GL version=" + GL11.glGetString(GL11.GL_VERSION)
                + " renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        for (int frame = 0; frame < 120; frame++) {
            GL11.glClearColor(0.2f, 0.1f, 0.5f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
        System.out.println("KPSTUB-GL frames ok");
    }
}
