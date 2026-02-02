# Testing Guidelines for RemoteAgentShell

## MockK Best Practices

### ⚠️ CRITICAL: Never use coEvery inside mockk() braces

**WRONG - Causes AbstractMethodError:**
```kotlin
val mock = mockk<Repo>(relaxed = true) {
    coEvery { suspendFunc() } returns value  // ❌ DON'T DO THIS
}
```

**CORRECT - Industry Standard:**
```kotlin
val mock = mockk<Repo>(relaxed = true)
coEvery { mock.suspendFunc() } returns value  // ✅ DO THIS
```

### Why This Matters

- `mockk() { ... }` creates anonymous subclass during construction
- `coEvery` inside braces tries to stub during initialization
- Suspend functions need fully-initialized mock proxy
- Results in `AbstractMethodError` at runtime

### Pattern for Setting Up Mocks

```kotlin
@Before
fun setup() {
    // 1. Always specify type explicitly: mockk<Interface>(...)
    repository = mockk<SessionRepository>(relaxed = true)
    
    // 2. Setup suspend functions with coEvery OUTSIDE the block
    coEvery { repository.suspendMethod() } returns value
    
    // 3. Setup regular properties with every
    every { repository.flowProperty } returns stateFlow
}
```

### Testing ViewModels with Coroutines

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MyViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    
    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // ... setup mocks using correct pattern
    }
    
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
    
    @Test
    fun `test case`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        // ... assertions
    }
}
```

## Common Issues & Solutions

### Issue: AbstractMethodError when mocking suspend functions
**Solution:** Use `mockk<Interface>(relaxed = true)` syntax and set up stubs outside the block.

### Issue: Tests fail with "no answer found"
**Solution:** Use `relaxed = true` to provide default answers for all methods.

### Issue: Flow collection not working in tests
**Solution:** Use `MutableStateFlow`/`MutableSharedFlow` for flow properties and update values directly.

## Quick Reference

| What | Syntax |
|------|--------|
| Mock with relaxed defaults | `mockk<Interface>(relaxed = true)` |
| Stub suspend function | `coEvery { mock.func() } returns value` |
| Stub regular function | `every { mock.func() } returns value` |
| Stub flow property | `every { mock.flow } returns stateFlow` |
| Verify suspend call | `coVerify { mock.func() }` |
| Verify regular call | `verify { mock.func() }` |

---

*Last updated: February 2026*
