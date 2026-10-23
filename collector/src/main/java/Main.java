import Controller.Controller;
import ORMUtil.DatabaseSetup;

public class Main {

    public static void main(String[] args) throws Exception {
        Controller ctrl = new Controller(DatabaseSetup.loadConfig(args));
        ctrl.startListening();

        Runtime.getRuntime().addShutdownHook(new Thread(ctrl::shutdown));
    }
}
