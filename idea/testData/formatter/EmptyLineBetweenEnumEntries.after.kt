enum class E1 { F, S, T }

enum class E2 {
    F, S, T
}

enum class E3 {
    F, S, T
}

enum class E4 {
    F {
    },
    S, T
}

enum class E5 { F, S {}, T }

enum class E6 {
    F, S, T {}
}

enum class E7 {
    F {

    },
    S, T
}

enum class E8 {
    // test
    A, // A
    B  // B
}

enum class E9 {
    F, S, T
}

enum class E10 {
    F, S, T;

    val x = 1
}

enum class TestEnum {
    /** A comment for the item #1. */
    ITEM1,

    /** A comment for the item #2. */
    ITEM2,

    /** A comment for the item #3. */
    ITEM3
}

// SET_TRUE: KEEP_LINE_BREAKS