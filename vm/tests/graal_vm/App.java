public class App {
    public static void main(String[] args) {
        System.out.println("Hello ");

        while (true) {
            System.out.println("Hello ");

            try {
                Thread.sleep(200);
            } catch (Exception ex) {
            }
        }
    }
}
//+UseG1GC