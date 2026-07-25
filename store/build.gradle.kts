plugins {
    id("mcorch.kotlin-conventions")
}

// Desired and observed state, behind an interface.
//
// sqlite-jdbc is `implementation`, deliberately: it keeps JDBC types out of any
// signature :core or :api can see, so the storage engine cannot leak through
// the interface that way. Note this is only half the guarantee — the interface
// and its SQLite implementation live in the same module, so under explicit-API
// mode a consumer can still name the implementation class directly. Splitting
// :store from its backend would close that, at the cost of a module CLAUDE.md
// does not list. Keep it honest by review until that trade is worth making.
// A distributed backend has to be droppable in behind the same interface.

dependencies {
    api(project(":schema"))

    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.coroutines.core)
}
