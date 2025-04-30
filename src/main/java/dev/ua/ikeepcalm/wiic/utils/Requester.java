package dev.ua.ikeepcalm.wiic.utils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ua.ikeepcalm.wiic.WIIC;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Requester {

    private static final String AUTH_TOKEN = WIIC.INSTANCE.getConfig().getString("auth-token");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static User fetchUser(String nickname) throws Exception {
        String url = String.format("%s?skip=%d&limit=%d&sort_by=created_at&order=desc&minecraft_nickname=%s",
                "https://api.uaproject.xyz/api/v2/users", 0, 1, nickname);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("accept", "application/json")
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            List<User> userList = MAPPER.readValue(response.body(), new TypeReference<List<User>>() {
            });
            return userList.getFirst();
        } else {
            throw new RuntimeException("Failed to fetch users: " + response.body());
        }
    }

    public static Balance fetchBalance(long userId) throws Exception {
        String url = String.format("%s/%d", "https://api.uaproject.xyz/api/v2/payments/balances/users", userId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("accept", "application/json")
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return MAPPER.readValue(response.body(), Balance.class);
        } else {
            throw new RuntimeException("Failed to fetch user balance: " + response.body());
        }
    }

    public static Application fetchApplication(int userId) throws Exception {
        String url = String.format("https://api.uaproject.xyz/api/v2/applications/users/%d", userId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("accept", "application/json")
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return MAPPER.readValue(response.body(), Application.class);
        } else {
            throw new RuntimeException("Failed to fetch user application: " + response.body());
        }
    }

    public static JsonNode getServiceDetails(int serviceId, Map<Integer, JsonNode> cache, ObjectMapper mapper) {
        if (cache.containsKey(serviceId)) {
            return cache.get(serviceId);
        }

        try {
            URL url = new URL("https://api.uaproject.xyz/api/v2/payments/services/details/" + serviceId);

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("accept", "application/json");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String inputLine;

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                JsonNode serviceDetails = mapper.readTree(response.toString());

                cache.put(serviceId, serviceDetails);

                return serviceDetails;
            } else {
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static List<String> fetchTransactions(int userId) throws Exception {
        String url = String.format(
                "https://api.uaproject.xyz/api/v2/payments/transactions?limit=10&sort_by=id&order=desc&user_id=%d&type=%s",
                userId,
                "purchase"
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("accept", "application/json")
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonNode jsonNode = MAPPER.readTree(response.body());

            List<String> transactions = new ArrayList<>();
            for (JsonNode transaction : jsonNode) {
                transactions.add(transaction.toString());
            }
            return transactions;
        } else {
            throw new RuntimeException("Failed to fetch transactions: " + response.body());
        }
    }

    public static Balance modifyBalance(long userId, long recipientId, double amount, String description) throws Exception {
        String url;
        String type;
        double absAmount = Math.abs(amount);

        if (amount >= 0) {
            url = "https://api.uaproject.xyz/api/v2/payments/transactions/deposit";
            type = "deposit";
        } else {
            url = "https://api.uaproject.xyz/api/v2/payments/transactions/withdrawal";
            type = "withdrawal";
        }

        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("amount", absAmount);
        requestBodyMap.put("recipient_id", recipientId);
        requestBodyMap.put("type", type);
        requestBodyMap.put("description", description);
        requestBodyMap.put("transaction_metadata", new HashMap<>());
        requestBodyMap.put("user_id", userId);

        String requestBody = MAPPER.writeValueAsString(requestBodyMap);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("accept", "application/json")
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200 || response.statusCode() == 201) {
            return fetchBalance(userId);
        } else {
            throw new RuntimeException("Failed to " + type + " balance: " + response.body());
        }
    }

    public static void resetBalance(int userId) {
        try {
            Balance balance = fetchBalance(userId);
            if (balance.getAmount().equals("0.00")) {
                return;
            }

            double currentAmount = Double.parseDouble(balance.getAmount());
            double withdrawAmount = -currentAmount;

            modifyBalance(userId, userId, withdrawAmount, "Баланс скинуто системою");
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset balance: " + e.getMessage(), e);
        }
    }

    @Getter
    @Setter
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class User {
        public long id;
        public long discord_id;
        public String minecraft_nickname;
        public boolean is_superuser;
        public String created_at;
        public String updated_at;
    }

    @Getter
    @Setter
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Balance {
        public long id;
        public int user_id;
        public String identifier;
        public String amount;
        public String created_at;
        public String updated_at;
    }

    @Getter
    @Setter
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Application {
        public String birth_date;
        public String launcher;
        public String server_source;
        public String private_server_experience;
        public String useful_skills;
        public String conflict_reaction;
        public String quiz_answer;
        public long id;
        public long user_id;
        public String status;
        public List<String> editable_fields;
        public String created_at;
        public String updated_at;
    }


}
