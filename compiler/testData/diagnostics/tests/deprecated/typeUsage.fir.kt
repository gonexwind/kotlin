@Deprecated("Class")
open class Obsolete {
    fun use() {}
}

@Deprecated("Class")
open class Obsolete2 @Deprecated("Constructor") constructor() {
    fun use() {}
}

interface Generic<T>


class Properties {

    var n : <!DEPRECATION!>Obsolete<!>
        get() = <!DEPRECATION!>Obsolete<!>()
        set(value) {}
}


