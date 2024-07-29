# Recommendation system use case

The sample scenario:
Orinoco Inc, retail sales company wants to display a widget on product pages of similar products that the user might be interested in buying.
They should recommend:
- Up to 6 highly rated products in the same category as that of the product the customer is currently viewing
- Only products that are in stock
- Products that the customer has bought before should be favoured
- <i>TODO: Avoid showing suggestions that have already been made in previous pageviews </i>

The data:
- Input: A clickstream (user id, product id, event time)
- Input: A stream of purchases (user id, product id, purchase date)
- Input: An inventory of products (product id, product name, product category, number in stock, rating)

Output: A stream of recommendations (user id, 6 product ids)

## Running the application

See the [README.md](../README.md)
