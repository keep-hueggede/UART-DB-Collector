import Controller.Controller;

public class Main {

    public static void main(String[] args) {
        Controller ctrl = new Controller();
        ctrl.startListening();

        Runtime.getRuntime().addShutdownHook(new Thread(ctrl::shutdown));
    }
}
