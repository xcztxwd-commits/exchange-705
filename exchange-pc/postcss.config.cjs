const path = require('node:path')

module.exports = {
  plugins: [
    require('tailwindcss')({ config: path.join(__dirname, 'tailwind.config.js') }),
    require('autoprefixer')(),
  ],
}
