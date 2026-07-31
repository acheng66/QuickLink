import { createStore } from 'vuex'

// 创建一个新的 store 实例
const store = createStore({
  state() {
    return {
      domain: 'localhost:8001'
    }
  }
})

export default store
