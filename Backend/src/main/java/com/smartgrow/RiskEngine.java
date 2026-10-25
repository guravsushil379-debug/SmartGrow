package com.smartgrow;

import org.springframework.stereotype.Service;
import java.util.List;

/** Rule-based inventory risk engine. No machine learning: only conditions and formulas. */
@Service
public class RiskEngine {

    public record Product(String name, double quantity, double sold, double price, Integer expiryDays) {}
    public record Request(String businessName, int periodDays, List<Product> products) {}
    public record Result(String name, double pending, double dailySales, Double depletionDays,
                         int riskScore, String level, double expectedLoss, String action, String suggestion) {}
    public record Summary(int total, int healthy, int low, int atRisk, int critical,
                          double expectedLoss, int reorderCount) {}
    public record Response(String businessName, Summary summary, List<Result> products) {}

    /** Intermediate numbers for one product. */
    private record Calc(Product p, double pending, double daily, Double depletion,
                        int expiry, int stock, int slow, double loss, double unsold) {}

    public Response analyze(Request req) {
        int period = req.periodDays();

        List<Calc> calcs = req.products().stream().map(p -> {
            double pending = Math.max(0, p.quantity() - p.sold());
            double daily = period > 0 ? p.sold() / period : 0;
            Double depletion = daily > 0 ? pending / daily : null;

            // Expiry risk: how much stock will still be unsold when it expires
            double unsold = 0;
            int expiry = 0;
            if (p.expiryDays() != null && pending > 0) {
                unsold = Math.max(0, pending - daily * p.expiryDays());
                double ratio = unsold / pending;
                int boost = ratio > 0 ? (p.expiryDays() <= 7 ? 20 : p.expiryDays() <= 14 ? 10 : 0) : 0;
                expiry = (int) Math.min(100, Math.round(ratio * 100 + boost));
            }

            // Low-stock risk: days until stock runs out
            int stock;
            if (pending == 0) stock = 100;
            else if (depletion == null) stock = 0;
            else stock = depletion <= 3 ? 100 : depletion <= 7 ? 80 : depletion <= 14 ? 50 : depletion <= 30 ? 20 : 0;

            // Slow-moving risk: share of stock sold so far
            double sellThrough = p.quantity() > 0 ? p.sold() / p.quantity() : 0;
            int slow = pending == 0 ? 0 : sellThrough < .2 ? 80 : sellThrough < .4 ? 50 : sellThrough < .6 ? 20 : 0;

            double loss = 0;
            if (p.expiryDays() != null && unsold > 0) loss = unsold * p.price();
            else if (slow >= 50) loss = pending * p.price() * 0.25;

            return new Calc(p, pending, daily, depletion, expiry, stock, slow, loss, unsold);
        }).toList();

        double totalValue = Math.max(1, calcs.stream().mapToDouble(c -> c.pending() * c.p().price()).sum());

        List<Result> results = calcs.stream().map(c -> {
            // Financial risk: loss as a share of total pending stock value
            int fin = (int) Math.min(100, Math.round(c.loss() / totalValue * 300));
            double weighted = .35 * c.expiry() + .30 * c.stock() + .15 * c.slow() + .20 * fin;
            int max = Math.max(Math.max(c.expiry(), c.stock()), Math.max(c.slow(), fin));
            int score = (int) Math.round(.6 * weighted + .4 * max);
            String level = score < 25 ? "Healthy" : score < 50 ? "Low Risk" : score < 75 ? "At Risk" : "Critical";
            String[] rec = recommend(c, level);
            return new Result(c.p().name(), c.pending(), c.daily(), c.depletion(),
                    score, level, c.loss(), rec[0], rec[1]);
        }).toList();

        Summary summary = new Summary(
                results.size(),
                count(results, "Healthy"), count(results, "Low Risk"),
                count(results, "At Risk"), count(results, "Critical"),
                results.stream().mapToDouble(Result::expectedLoss).sum(),
                (int) results.stream().filter(r -> r.action().equals("Reorder")).count());

        return new Response(req.businessName(), summary, results);
    }

    private static int count(List<Result> rs, String level) {
        return (int) rs.stream().filter(r -> r.level().equals(level)).count();
    }

    private static double round1(double x) { return Math.round(x * 10) / 10.0; }

    private static String num(double x) {
        return x == Math.floor(x) ? String.valueOf((long) x) : String.valueOf(x);
    }

    private static String[] recommend(Calc c, String level) {
        double daily = round1(c.daily());
        long reorderQty = Math.max(1, (long) Math.ceil(c.daily() * 21 - c.pending()));

        if (c.pending() == 0)
            return new String[]{"Reorder", "Out of stock. Reorder about " + Math.max(1, (long) Math.ceil(c.daily() * 21))
                    + " units to cover the next 3 weeks."};
        if (level.equals("Healthy"))
            return new String[]{"No action", "Stock is moving at a healthy pace. Keep monitoring."};

        if (c.expiry() >= c.stock() && c.expiry() >= c.slow()) {
            double ratio = c.unsold() / c.pending();
            int d = ratio > .6 ? 30 : ratio > .3 ? 20 : 10;
            return new String[]{"Discount + sell first", "About " + (long) Math.ceil(c.unsold()) + " of " + num(c.pending())
                    + " units may expire unsold. Sell these first and apply a " + d + "% discount."};
        }
        if (c.stock() >= c.slow())
            return new String[]{"Reorder", "At " + num(daily) + " units/day, stock runs out in " + num(round1(c.depletion()))
                    + " days. Reorder about " + reorderQty + " units."};
        if (c.p().sold() == 0)
            return new String[]{"Investigate", "No sales recorded. Check pricing, shelf placement, or whether the item is still wanted."};
        return new String[]{"Run promotion", "Only " + Math.round(c.p().sold() / c.p().quantity() * 100)
                + "% of stock has sold. Try a promotion or bundle offer."};
    }
}