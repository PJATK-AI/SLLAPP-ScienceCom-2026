<script setup lang="ts">
import { ref } from 'vue'

const count = ref(0)
</script>

<template>
    <main>
        <h1>SLLAPP</h1>
        <button type="button" @click="count++">Clicked {{ count }} times</button>
    </main>
</template>
