package io.brace;

public class Cli {
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        switch (args[0]) {
            case "new" -> {
                if (args.length < 2) {
                    System.err.println("Usage: brace new <project-name>");
                    System.exit(1);
                }
                ProjectGenerator.generate(args[1]);
            }
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("Brace CLI v0.1.0");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  brace new <name>    Create a new Brace project");
    }
}
