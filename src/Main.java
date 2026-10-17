// Start the program from here:  java Main
public class Main {

    public static void main(String[] args) {
        try {
            Server.start(8080);
        } catch (Exception e) {
            System.out.println("Could not start the program:");
            e.printStackTrace();
        }
    }
}
