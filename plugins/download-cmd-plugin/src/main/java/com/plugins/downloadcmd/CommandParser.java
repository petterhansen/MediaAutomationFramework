package com.plugins.downloadcmd;

/**
 * Parses /dl command arguments into DownloadRequest
 */
public class CommandParser {
    
    /**
     * Parse /dl command arguments
     * Format: /dl {amount} [source:]{query} [vid|img] [service]
     * 
     * @param args Command arguments (excluding "/dl")
     * @return Parsed DownloadRequest
     * @throws IllegalArgumentException if syntax is invalid
     */
    public static DownloadRequest parse(String[] args) throws IllegalArgumentException {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                "Usage: /dl {amount} {query} [vid|img] [service]\n" +
                "Examples:\n" +
                "  /dl 1 alexapearl\n" +
                "  /dl 5 coomer:alexapearl\n" +
                "  /dl 10 alexapearl vid\n" +
                "  /dl 50 alexapearl img onlyfans"
            );
        }
        
        // Parse amount
        int amount;
        try {
            amount = Integer.parseInt(args[0]);
            if (amount <= 0) {
                throw new IllegalArgumentException("Amount must be positive");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid amount: " + args[0]);
        }
        
        // Parse source:query or just query
        String rawQuery = args[1];
        String source = null;
        String query;
        
        if (rawQuery.contains(":")) {
            String[] parts = rawQuery.split(":", 2);
            source = parts[0].toLowerCase();
            query = parts[1];
            
            // Validate source
            if (!isValidSource(source)) {
                throw new IllegalArgumentException(
                    "Invalid source: " + source + ". " +
                    "Valid: coomer, kemono, youtube, booru, browser"
                );
            }
        } else {
            query = rawQuery;
            // source remains null = auto-detect
        }
        
        // Parse optional filetype
        String filetype = null;
        if (args.length > 2) {
            String arg2 = args[2].toLowerCase();
            if (arg2.equals("vid") || arg2.equals("img")) {
                filetype = arg2;
            } else if (!isValidService(arg2)) {
                // If it's not a filetype and not a service, it's invalid
                throw new IllegalArgumentException(
                    "Invalid argument: " + args[2] + ". " +
                    "Expected: vid, img, or service name"
                );
            }
        }
        
        // Parse optional service
        String service = null;
        int serviceIndex = filetype != null ? 3 : 2;
        if (args.length > serviceIndex) {
            String argService = args[serviceIndex].toLowerCase();
            if (isValidService(argService)) {
                service = argService;
            } else {
                throw new IllegalArgumentException("Invalid service: " + argService);
            }
        }
        
        return new DownloadRequest(amount, source, query, filetype, service);
    }
    
    private static boolean isValidSource(String source) {
        return source.equals("coomer") ||
               source.equals("kemono") ||
               source.equals("youtube") ||
               source.equals("yt") || // Shorthand
               source.equals("booru") ||
               source.equals("r34") || // Alias
               source.equals("browser");
    }
    
    private static boolean isValidService(String service) {
        return service.equals("onlyfans") ||
               service.equals("patreon") ||
               service.equals("fansly");
    }
}
