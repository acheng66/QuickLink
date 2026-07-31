<template>
  <main class="login-page">
    <section class="brand-panel" aria-label="QuickLink 产品介绍">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">
          <i></i><i></i><i></i>
        </span>
        <span>QuickLink</span>
      </div>

      <div class="brand-copy">
        <p class="eyebrow">LINK OPERATIONS</p>
        <h1>让每一次点击<br />都有迹可循</h1>
        <p class="description">
          创建、管理并分析你的短链接。用更短的路径，连接更远的内容。
        </p>
        <div class="route-preview" aria-hidden="true">
          <span class="route-dot"></span>
          <span class="route-line"></span>
          <span class="route-code">ql.ink / A7k2Px</span>
          <span class="route-arrow">→</span>
          <span class="route-target">目标页面</span>
        </div>
      </div>

      <p class="brand-footer">Quick links. Clear insights.</p>
    </section>

    <section class="form-panel">
      <div class="mobile-brand">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>
        <span>QuickLink</span>
      </div>

      <div class="form-card">
        <div class="form-heading">
          <p>{{ isLogin ? '欢迎回来' : '创建你的账号' }}</p>
          <h2>{{ isLogin ? '登录 QuickLink' : '注册 QuickLink' }}</h2>
          <span>{{ isLogin ? '继续管理你的链接与访问数据' : '几步即可开始管理你的短链接' }}</span>
        </div>

        <el-form
          v-if="isLogin"
          ref="loginFormRef1"
          :model="loginForm"
          :rules="loginFormRule"
          label-position="top"
          @keyup.enter="login(loginFormRef1)"
        >
          <el-form-item label="用户名" prop="username">
            <el-input v-model="loginForm.username" placeholder="请输入用户名" clearable size="large">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="loginForm.password"
              type="password"
              placeholder="请输入密码"
              show-password
              clearable
              size="large"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <div class="form-options">
            <el-checkbox v-model="checked">记住密码</el-checkbox>
          </div>
          <el-button
            class="submit-button"
            :loading="loading"
            type="primary"
            size="large"
            @click="login(loginFormRef1)"
          >
            登录
            <el-icon class="button-arrow"><ArrowRight /></el-icon>
          </el-button>
        </el-form>

        <el-form
          v-else
          ref="loginFormRef2"
          :model="addForm"
          :rules="addFormRule"
          label-position="top"
          class="register-form"
        >
          <div class="form-grid">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="addForm.username" placeholder="设置用户名" clearable />
            </el-form-item>
            <el-form-item label="姓名" prop="realName">
              <el-input v-model="addForm.realName" placeholder="请输入姓名" clearable />
            </el-form-item>
            <el-form-item label="邮箱" prop="mail">
              <el-input v-model="addForm.mail" placeholder="name@example.com" clearable />
            </el-form-item>
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="addForm.phone" placeholder="请输入手机号" clearable />
            </el-form-item>
          </div>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="addForm.password"
              type="password"
              placeholder="至少 8 位"
              show-password
              clearable
            />
          </el-form-item>
          <el-button
            class="submit-button"
            :loading="loading"
            type="primary"
            size="large"
            @click="addUser(loginFormRef2)"
          >
            创建账号
            <el-icon class="button-arrow"><ArrowRight /></el-icon>
          </el-button>
        </el-form>

        <div class="switch-mode">
          <span>{{ isLogin ? '还没有账号？' : '已经有账号？' }}</span>
          <button type="button" @click="changeLogin">
            {{ isLogin ? '立即注册' : '返回登录' }}
          </button>
        </div>
      </div>
    </section>
  </main>
</template>

<script setup>
import { ref, reactive, getCurrentInstance } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { setToken, setUsername, getUsername } from '@/core/auth.js'

const { proxy } = getCurrentInstance()
const API = proxy.$API
const router = useRouter()
const loginFormRef1 = ref()
const loginFormRef2 = ref()
const loading = ref(false)
const checked = ref(true)
const isLogin = ref(true)

const loginForm = reactive({
  username: 'admin',
  password: 'admin123456'
})

const addForm = reactive({
  username: '',
  password: '',
  realName: '',
  phone: '',
  mail: ''
})

const addFormRule = reactive({
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号', trigger: 'blur' }
  ],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 15, message: '密码长度应为 8–15 位', trigger: 'blur' }
  ],
  mail: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9][\w-]*@[A-Za-z0-9-]+(\.[A-Za-z]{2,})+$/,
      message: '请输入正确的邮箱',
      trigger: 'blur'
    }
  ],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }]
})

const loginFormRule = reactive({
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 15, message: '密码长度应为 8–15 位', trigger: 'blur' }
  ]
})

const saveLogin = (username, token) => {
  if (!token) return
  setToken(token)
  setUsername(username)
  localStorage.setItem('token', token)
  localStorage.setItem('username', username)
}

const addUser = (formEl) => {
  if (!formEl) return
  formEl.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const existsResponse = await API.user.hasUsername({ username: addForm.username })
      if (existsResponse.data.success === false) {
        ElMessage.warning('用户名已存在')
        return
      }
      const registerResponse = await API.user.addUser(addForm)
      if (registerResponse.data.success === false) {
        ElMessage.warning(registerResponse.data.message)
        return
      }
      const loginResponse = await API.user.login({
        username: addForm.username,
        password: addForm.password
      })
      saveLogin(addForm.username, loginResponse?.data?.data?.token)
      ElMessage.success('注册成功，已为你登录')
      router.push('/home')
    } finally {
      loading.value = false
    }
  })
}

const login = (formEl) => {
  if (!formEl) return
  formEl.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const response = await API.user.login(loginForm)
      if (response.data.code === '0') {
        saveLogin(loginForm.username, response?.data?.data?.token)
        ElMessage.success('登录成功')
        router.push('/home')
      } else if (response.data.message === '用户已登录') {
        if (getUsername() === loginForm.username) {
          router.push('/home')
        } else {
          ElMessage.warning('该用户已在其他位置登录')
        }
      } else {
        ElMessage.error(response.data.message || '用户名或密码不正确')
      }
    } finally {
      loading.value = false
    }
  })
}

const changeLogin = () => {
  isLogin.value = !isLogin.value
}
</script>

<style lang="scss" scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(420px, 0.95fr) minmax(520px, 1.05fr);
  background: #f5f7fb;
  color: #172033;
}

.brand-panel {
  position: relative;
  overflow: hidden;
  padding: 46px 58px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  color: #fff;
  background:
    radial-gradient(circle at 18% 82%, rgba(45, 212, 191, 0.2), transparent 30%),
    radial-gradient(circle at 87% 18%, rgba(129, 140, 248, 0.28), transparent 34%),
    linear-gradient(145deg, #111b42 0%, #1b2d68 52%, #2645a6 100%);
}

.brand-panel::before,
.brand-panel::after {
  content: '';
  position: absolute;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 50%;
}

.brand-panel::before {
  width: 520px;
  height: 520px;
  right: -260px;
  top: 12%;
}

.brand-panel::after {
  width: 300px;
  height: 300px;
  right: -150px;
  top: calc(12% + 110px);
}

.brand,
.mobile-brand {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 13px;
  font-size: 22px;
  font-weight: 750;
  letter-spacing: -0.4px;
}

.brand-mark {
  width: 38px;
  height: 38px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border-radius: 12px;
  background: #2dd4bf;
  box-shadow: 0 10px 30px rgba(45, 212, 191, 0.25);

  i {
    display: block;
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: #112050;
  }

  i:nth-child(2) {
    width: 9px;
    border-radius: 5px;
  }
}

.brand-copy {
  position: relative;
  z-index: 1;
  max-width: 560px;
}

.eyebrow {
  margin-bottom: 22px;
  color: #78f1df;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.22em;
}

.brand-copy h1 {
  margin: 0;
  color: #fff;
  font-size: clamp(46px, 5vw, 72px);
  font-weight: 750;
  line-height: 1.12;
  letter-spacing: -0.06em;
}

.description {
  max-width: 430px;
  margin-top: 26px;
  color: rgba(236, 241, 255, 0.75);
  font-size: 16px;
  line-height: 1.85;
}

.route-preview {
  width: fit-content;
  margin-top: 52px;
  padding: 14px 18px;
  display: flex;
  align-items: center;
  gap: 11px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 14px;
  background: rgba(9, 18, 51, 0.3);
  backdrop-filter: blur(8px);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.route-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #2dd4bf;
  box-shadow: 0 0 0 5px rgba(45, 212, 191, 0.12);
}

.route-line {
  width: 24px;
  height: 1px;
  background: rgba(255, 255, 255, 0.22);
}

.route-arrow {
  color: #78f1df;
}

.route-target,
.brand-footer {
  color: rgba(255, 255, 255, 0.48);
}

.brand-footer {
  position: relative;
  z-index: 1;
  font-size: 12px;
  letter-spacing: 0.08em;
}

.form-panel {
  min-height: 100vh;
  padding: 48px clamp(40px, 8vw, 120px);
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    linear-gradient(rgba(48, 73, 153, 0.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(48, 73, 153, 0.035) 1px, transparent 1px),
    #f8faff;
  background-size: 32px 32px;
}

.form-card {
  width: min(100%, 470px);
  padding: 42px;
  border: 1px solid rgba(59, 83, 155, 0.1);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 24px 70px rgba(30, 49, 102, 0.1);
}

.form-heading {
  margin-bottom: 30px;

  p {
    margin-bottom: 8px;
    color: #3858c8;
    font-size: 13px;
    font-weight: 700;
  }

  h2 {
    margin: 0;
    color: #172033;
    font-size: 30px;
    font-weight: 750;
    line-height: 1.3;
    letter-spacing: -0.04em;
  }

  span {
    display: block;
    margin-top: 9px;
    color: #7a8499;
    font-size: 14px;
  }
}

.form-options {
  margin: -3px 0 20px;
}

.submit-button {
  width: 100%;
  height: 48px;
  margin-top: 4px;
  border-radius: 12px;
  font-weight: 650;
  box-shadow: 0 12px 24px rgba(56, 88, 200, 0.2);
}

.button-arrow {
  margin-left: 8px;
}

.switch-mode {
  margin-top: 24px;
  display: flex;
  justify-content: center;
  gap: 6px;
  color: #7a8499;
  font-size: 14px;

  button {
    padding: 0;
    border: 0;
    color: #3858c8;
    background: transparent;
    font-weight: 650;
    cursor: pointer;
  }
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}

.mobile-brand {
  display: none;
}

:deep(.el-form-item__label) {
  padding-bottom: 7px;
  color: #34405a;
  font-weight: 600;
}

:deep(.el-input__wrapper) {
  border-radius: 11px;
}

@media (max-width: 900px) {
  .login-page {
    display: block;
  }

  .brand-panel {
    display: none;
  }

  .form-panel {
    min-height: 100vh;
    padding: 32px 20px;
    flex-direction: column;
    align-items: stretch;
  }

  .mobile-brand {
    display: flex;
    margin: 0 auto 28px;
    color: #172033;
  }

  .form-card {
    margin: 0 auto;
    padding: 30px 24px;
  }
}

@media (max-width: 520px) {
  .form-grid {
    display: block;
  }
}

@media (prefers-reduced-motion: no-preference) {
  .form-card {
    animation: card-in 0.45s ease-out both;
  }

  @keyframes card-in {
    from {
      opacity: 0;
      transform: translateY(14px);
    }
  }
}
</style>
