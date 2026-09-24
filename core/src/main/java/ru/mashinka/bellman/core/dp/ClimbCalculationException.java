package ru.mashinka.bellman.core.dp;

//#наследование - extends RuntimeException, свой тип исключения
//#исключения - два разных типа на два разных случая, см. ниже
//
// данные корректны, но решения не существует: высота выше потолка, масса великовата,
// ограничения по скорости разрывают траекторию
//
// в проекте два разных типа ошибок, и это осознанно:
//   IllegalArgumentException   - данные бессмысленные (крейсерская ниже начальной)
//   ClimbCalculationException  - данные нормальные, просто задача нерешаема
// веб-слой переводит их в разные коды ответа, 400 и 422 соответственно
//
// наследуемся от RuntimeException, а не от Exception: проверяемое исключение
// заставило бы писать throws во всей цепочке вызовов, хотя поймать его осмысленно
// можно только наверху, в контроллере
public class ClimbCalculationException extends RuntimeException {

    // нужен любому Serializable-классу, а исключения сериализуемы по наследству.
    // без него компилятор с -Xlint выдаёт предупреждение
    private static final long serialVersionUID = 1L;

    public ClimbCalculationException(String message) {
        super(message);
    }
}
