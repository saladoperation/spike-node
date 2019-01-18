path = require("path");

module.exports = {
    entry: "./src/js/index.js",
    module: {
        rules: [
            {
                test: /\.css$/,
                use: ["style-loader", "css-loader"],
            },
            {
                test: /\.(ttf|woff|woff2)$/,
                use: ["file-loader"],
            }
        ]
    },
    output: {
        path: path.resolve(__dirname, "resources/public/webpack"),
        filename: "index_bundle.js",
        publicPath: "webpack/"
    }
};
