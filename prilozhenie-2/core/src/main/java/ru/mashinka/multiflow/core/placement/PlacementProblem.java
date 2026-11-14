package ru.mashinka.multiflow.core.placement;

// задача размещения производства: m новых видов продукции на n предприятиях,
// на каждом предприятии - не больше одного вида, каждый вид - ровно на одном предприятии
//
// Даны издержки производства и издержки сбыта единицы продукции (вид x предприятие),
// плановый годовой объём и плановая цена единицы каждого вида. Отсюда:
//   общие издержки на единицу = производство + сбыт
//   прибыль на единицу        = цена - общие издержки
//   общая прибыль             = прибыль на единицу x объём
// Виды и предприятия внутри нумеруются с нуля, пользователю показываются с единицы.
//#инкапсуляция - таблицы private, наружу только копии и чтение по клеткам
public final class PlacementProblem {

    public static final int MIN_PRODUCTS = 2;
    public static final int MAX_PRODUCTS = 10;
    public static final int MIN_PLANTS = 2;
    public static final int MAX_PLANTS = 10;
    // издержки и цена единицы, руб.
    public static final int MAX_MONEY = 1_000_000;
    public static final int MAX_VOLUME = 100_000_000;

    private final int[][] production;
    private final int[][] sales;
    private final int[] volume;
    private final int[] price;

    private PlacementProblem(int[][] production, int[][] sales, int[] volume, int[] price) {
        this.production = production;
        this.sales = sales;
        this.volume = volume;
        this.price = price;
    }

    //#валидация - отсекаем плохой ввод до расчёта; текст ошибки сразу для пользователя
    public static PlacementProblem of(int[][] production, int[][] sales, int[] volume, int[] price) {
        if (production == null) {
            throw new IllegalArgumentException("Таблица издержек производства не задана");
        }
        if (sales == null) {
            throw new IllegalArgumentException("Таблица издержек сбыта не задана");
        }
        if (volume == null) {
            throw new IllegalArgumentException("Плановый объём не задан");
        }
        if (price == null) {
            throw new IllegalArgumentException("Плановая цена не задана");
        }

        int m = production.length;
        if (m < MIN_PRODUCTS || m > MAX_PRODUCTS) {
            throw new IllegalArgumentException("Видов продукции должно быть от " + MIN_PRODUCTS + " до "
                    + MAX_PRODUCTS + ", а задано " + m);
        }
        int n = production[0] == null ? 0 : production[0].length;
        if (n < MIN_PLANTS || n > MAX_PLANTS) {
            throw new IllegalArgumentException("Предприятий должно быть от " + MIN_PLANTS + " до "
                    + MAX_PLANTS + ", а задано " + n);
        }
        if (m > n) {
            throw new IllegalArgumentException("Видов продукции (" + m + ") больше, чем предприятий (" + n
                    + "): на каждом предприятии производится не больше одного вида");
        }

        int[][] productionCopy = copyTable(production, m, n, "Издержки производства");
        int[][] salesCopy = copyTable(sales, m, n, "Издержки сбыта");
        int[] volumeCopy = copyColumn(volume, m, "Плановый объём", 1, MAX_VOLUME);
        int[] priceCopy = copyColumn(price, m, "Плановая цена", 0, MAX_MONEY);
        return new PlacementProblem(productionCopy, salesCopy, volumeCopy, priceCopy);
    }

    private static int[][] copyTable(int[][] table, int m, int n, String title) {
        if (table.length != m) {
            throw new IllegalArgumentException(title + ": " + table.length + " строк вместо " + m);
        }
        int[][] copy = new int[m][n];
        for (int i = 0; i < m; i++) {
            int length = table[i] == null ? 0 : table[i].length;
            if (length != n) {
                throw new IllegalArgumentException(title + ", вид " + (i + 1) + ": " + length
                        + " чисел вместо " + n);
            }
            for (int j = 0; j < n; j++) {
                int value = table[i][j];
                String where = title + ", вид " + (i + 1) + ", предприятие " + (j + 1);
                if (value < 0) {
                    throw new IllegalArgumentException(where + ": не может быть отрицательным (" + value + ")");
                }
                if (value > MAX_MONEY) {
                    throw new IllegalArgumentException(where + ": не больше 1 000 000 (" + value + ")");
                }
                copy[i][j] = value;
            }
        }
        return copy;
    }

    private static int[] copyColumn(int[] column, int m, String title, int min, int max) {
        if (column.length != m) {
            throw new IllegalArgumentException(title + ": " + column.length + " чисел вместо " + m);
        }
        int[] copy = new int[m];
        for (int i = 0; i < m; i++) {
            int value = column[i];
            String where = title + ", вид " + (i + 1);
            if (value < min) {
                throw new IllegalArgumentException(where + ": " + (min == 0
                        ? "не может быть отрицательной (" + value + ")"
                        : "должен быть не меньше " + min + " (" + value + ")"));
            }
            if (value > max) {
                throw new IllegalArgumentException(where + ": не больше " + group(max) + " (" + value + ")");
            }
            copy[i] = value;
        }
        return copy;
    }

    // 100000000 -> «100 000 000» для текста ошибки
    private static String group(int value) {
        String digits = Integer.toString(value);
        StringBuilder text = new StringBuilder();
        for (int k = 0; k < digits.length(); k++) {
            if (k > 0 && (digits.length() - k) % 3 == 0) {
                text.append(' ');
            }
            text.append(digits.charAt(k));
        }
        return text.toString();
    }

    public int products() {
        return production.length;
    }

    public int plants() {
        return production[0].length;
    }

    public int production(int product, int plant) {
        return production[product][plant];
    }

    public int sales(int product, int plant) {
        return sales[product][plant];
    }

    public int volume(int product) {
        return volume[product];
    }

    public int price(int product) {
        return price[product];
    }

    // общие издержки на единицу, руб.
    public int unitCost(int product, int plant) {
        return production[product][plant] + sales[product][plant];
    }

    // прибыль на единицу, руб.; бывает отрицательной, если продукция убыточна на этом предприятии
    public int unitProfit(int product, int plant) {
        return price[product] - unitCost(product, plant);
    }

    // общая годовая прибыль, руб.; long: 2 000 000 x 100 000 000 в int не помещается
    public long profit(int product, int plant) {
        return (long) unitProfit(product, plant) * volume[product];
    }

    public int[][] getProduction() {
        return copy(production);
    }

    public int[][] getSales() {
        return copy(sales);
    }

    public int[] getVolume() {
        return volume.clone();
    }

    public int[] getPrice() {
        return price.clone();
    }

    public int[][] unitCostTable() {
        int[][] table = new int[products()][plants()];
        for (int i = 0; i < products(); i++) {
            for (int j = 0; j < plants(); j++) {
                table[i][j] = unitCost(i, j);
            }
        }
        return table;
    }

    public int[][] unitProfitTable() {
        int[][] table = new int[products()][plants()];
        for (int i = 0; i < products(); i++) {
            for (int j = 0; j < plants(); j++) {
                table[i][j] = unitProfit(i, j);
            }
        }
        return table;
    }

    public long[][] profitTable() {
        long[][] table = new long[products()][plants()];
        for (int i = 0; i < products(); i++) {
            for (int j = 0; j < plants(); j++) {
                table[i][j] = profit(i, j);
            }
        }
        return table;
    }

    private static int[][] copy(int[][] table) {
        int[][] result = new int[table.length][];
        for (int i = 0; i < table.length; i++) {
            result[i] = table[i].clone();
        }
        return result;
    }
}
